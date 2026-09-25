package br.edu.aps.manancial;
import com.google.gson.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Ingestao e transmissao independem do banco. A fila em disco precede o aceite HTTP. */
final class Fluxo implements AutoCloseable {
    static final Gson JSON=new GsonBuilder().serializeNulls().setStrictness(Strictness.STRICT).create();
    private final Banco banco;
    final TempoReal sse=new TempoReal();
    final AlertasRecentes recentes=new AlertasRecentes();
    final FilaPersistente fila;
    private final Path gruposPasta;
    private final Map<String,JsonObject> grupos=new LinkedHashMap<>();
    private final ScheduledExecutorService gravador=Executors.newSingleThreadScheduledExecutor(r->daemon(r,"gravacao"));
    private final ScheduledExecutorService agrupador=Executors.newSingleThreadScheduledExecutor(r->daemon(r,"agrupamento"));
    private final FileChannel canal;
    private final FileLock trava;
    private volatile boolean encerrado;
    private long ultimoErro;
    private final int limite=Integer.getInteger("aps.fila.limite",10000);
    static class Conflito extends IllegalArgumentException { Conflito(String mensagem){super(mensagem);} }
    private static Thread daemon(Runnable r,String nome){Thread t=new Thread(r,"manancial-"+nome);t.setDaemon(true);return t;}

    Fluxo(Banco banco,Path arquivo) throws IOException {
        this.banco=banco;
        Path pasta=arquivo.toAbsolutePath().resolveSibling(arquivo.getFileName()+".fila-manancial");
        Files.createDirectories(pasta);
        canal=FileChannel.open(pasta.resolve("execucao.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        FileLock obtida;
        try {obtida=canal.tryLock();}catch(OverlappingFileLockException e){canal.close();throw new IOException("Ja existe uma API usando esta fila.",e);}
        if(obtida==null){canal.close();throw new IOException("Ja existe uma API usando esta fila.");}trava=obtida;
        fila=new FilaPersistente(pasta.resolve("pendentes"));
        gruposPasta=pasta.resolve("grupos");Files.createDirectories(gruposPasta);
        try(var arquivos=Files.list(gruposPasta)) {
            for(Path p:arquivos.filter(a->a.toString().endsWith(".json")).toList()) {
                JsonObject g=FilaPersistente.ler(p);grupos.put(g.get("ponto_id").getAsString(),g);
                for(JsonElement e:g.getAsJsonArray("medicoes"))fila.adicionar(e.getAsJsonObject());
            }
        }
        gravador.scheduleWithFixedDelay(this::gravarPendentes,50,100,TimeUnit.MILLISECONDS);
        agrupador.scheduleWithFixedDelay(()->{try{expirar();}catch(Exception e){Log.info("Agrupamento pendente: "+e.getClass().getSimpleName());}},100,50,TimeUnit.MILLISECONDS);
    }
    synchronized Map<String,Object> receber(String corpo) throws IOException {
        if(encerrado)throw new IOException("API encerrando.");
        if(fila.tamanho()+grupos.size()>=limite)throw new IOException("Fila cheia; tente novamente mais tarde.");
        if("manancial".equals("alagamento"))return receberMedicao(corpo);
        Leitura leitura=Leitura.deJson(corpo);
        JsonObject evento=evento("leitura",JSON.toJsonTree(leitura).getAsJsonObject());
        evento.add("alertas",alertas(leitura,evento));
        fila.adicionar(evento);
        publicar(evento);
        Log.info("Leitura recebida: "+evento.get("evento_id").getAsString()+" | gravacao pendente.");
        return Map.of("mensagem","Leitura recebida com sucesso; gravacao agendada.","evento_id",evento.get("evento_id").getAsString(),"persistencia","pendente");
    }
    private JsonObject evento(String tipo,JsonObject dados) {
        JsonObject e=new JsonObject();e.addProperty("evento_id",UUID.randomUUID().toString());e.addProperty("tipo_evento",tipo);
        e.addProperty("recebido_em",Instant.now().toString());e.add("leitura",dados);e.add("alertas",new JsonArray());return e;
    }
    private JsonArray alertas(Leitura leitura,JsonObject evento) {
        var regra=RegrasAlerta.avaliar(leitura);JsonArray lista=new JsonArray();if(regra.isEmpty())return lista;
        JsonObject a=JSON.toJsonTree(regra).getAsJsonObject();a.addProperty("evento_id",evento.get("evento_id").getAsString()+"-alerta");
        a.addProperty("servico","manancial");a.addProperty("criado_em",evento.get("recebido_em").getAsString());a.add("leitura",evento.get("leitura").deepCopy());lista.add(a);return lista;
    }
    private void publicar(JsonObject evento) {
        for(JsonElement e:evento.getAsJsonArray("alertas")){recentes.adicionar(e.getAsJsonObject());Log.info("Alerta gerado: "+e.getAsJsonObject().get("mensagem").getAsString());}
        if("leitura".equals(evento.get("tipo_evento").getAsString())) {
            JsonObject dados=evento.getAsJsonObject("leitura").deepCopy();dados.addProperty("evento_id",evento.get("evento_id").getAsString());
            sse.publicar(evento.get("evento_id").getAsString(),JSON.toJson(dados));Log.info("Leitura publicada no SSE: "+evento.get("evento_id").getAsString());
        }
    }
    private Map<String,Object> receberMedicao(String corpo) throws IOException {
        JsonObject dados=validarMedicao(corpo);
        expirar();String ponto=dados.get("ponto_id").getAsString();JsonObject atual=grupos.get(ponto);
        if(atual!=null) {
            Instant menor=Instant.parse(atual.get("menor").getAsString()),maior=Instant.parse(atual.get("maior").getAsString());
            Instant momento=OffsetDateTime.parse(dados.get("timestamp").getAsString()).toInstant();
            if(momento.isBefore(menor))menor=momento;if(momento.isAfter(maior))maior=momento;
            if(Duration.between(menor,maior).compareTo(Duration.ofSeconds(3))>0)throw new Conflito("Horario fora da janela de 3 segundos deste ponto; envie uma medicao atual no proximo conjunto.");
            for(JsonElement item:atual.getAsJsonArray("medicoes"))
                if(item.getAsJsonObject().getAsJsonObject("leitura").get("tipo").equals(dados.get("tipo")))throw new Conflito("Este tipo de sensor ja foi recebido neste conjunto.");
        } else if(grupos.size()>=1000)throw new IOException("Limite de pontos aguardando sensores atingido.");
        JsonObject medicao=evento("medicao",dados);
        medicao.add("alertas",alertas(parcial(dados),medicao));
        JsonObject grupo=atual==null?new JsonObject():atual.deepCopy();
        Instant instante=OffsetDateTime.parse(dados.get("timestamp").getAsString()).toInstant();
        if(atual==null){grupo.addProperty("evento_id",UUID.randomUUID().toString());grupo.addProperty("ponto_id",ponto);grupo.addProperty("aberto_em",Instant.now().toEpochMilli());grupo.addProperty("menor",instante.toString());grupo.addProperty("maior",instante.toString());grupo.add("medicoes",new JsonArray());}
        if(instante.isBefore(Instant.parse(grupo.get("menor").getAsString())))grupo.addProperty("menor",instante.toString());
        if(instante.isAfter(Instant.parse(grupo.get("maior").getAsString())))grupo.addProperty("maior",instante.toString());
        grupo.getAsJsonArray("medicoes").add(medicao);
        FilaPersistente.escrever(gruposPasta.resolve(grupo.get("evento_id").getAsString()+".json"),grupo);
        grupos.put(ponto,grupo);fila.adicionar(medicao);publicar(medicao);
        Log.info("Medicao recebida: ponto="+ponto+" | sensor="+dados.get("sensor_id").getAsString()+" | "+dados.get("tipo").getAsString()+"="+dados.get("valor")+" "+dados.get("unidade").getAsString());
        if(grupo.getAsJsonArray("medicoes").size()==3)fechar(ponto,grupo);
        return Map.of("mensagem","Medicao recebida; processamento agendado.","evento_id",medicao.get("evento_id").getAsString(),"persistencia","pendente");
    }
    private Leitura parcial(JsonObject d) {
        String tipo=d.get("tipo").getAsString();Double v=d.get("valor").getAsDouble();
        return new Leitura(d.get("ponto_id").getAsString(),d.get("timestamp").getAsString(),"nivel_corrego".equals(tipo)?v:null,"chuva".equals(tipo)?v:null,"velocidade_agua".equals(tipo)?v:null);
    }
    private JsonObject validarMedicao(String corpo) {
        JsonElement parsed;
        try{parsed=JSON.fromJson(corpo,JsonElement.class);}catch(JsonParseException e){throw new IllegalArgumentException("JSON invalido.");}
        if(parsed==null||!parsed.isJsonObject())throw new IllegalArgumentException("Envie um objeto JSON de medicao individual.");
        JsonObject d=parsed.getAsJsonObject();Set<String> campos=Set.of("sensor_id","ponto_id","tipo","valor","unidade","timestamp");
        if(!d.keySet().equals(campos))throw new IllegalArgumentException("Use somente sensor_id, ponto_id, tipo, valor, unidade e timestamp.");
        for(String campo:campos)if(!campo.equals("valor")) {
            JsonElement v=d.get(campo);if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString()||v.getAsString().isBlank()||v.getAsString().length()>128)throw new IllegalArgumentException("Texto invalido: "+campo);
        }
        Map<String,String> unidades=Map.of("nivel_corrego","cm","chuva","mm","velocidade_agua","m/s");
        if(!Objects.equals(unidades.get(d.get("tipo").getAsString()),d.get("unidade").getAsString()))throw new IllegalArgumentException("Tipo/unidade invalido: nivel_corrego/cm, chuva/mm, velocidade_agua/m/s.");
        JsonElement v=d.get("valor");if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber()||!Double.isFinite(v.getAsDouble())||v.getAsDouble()<0)throw new IllegalArgumentException("valor deve ser numero finito maior ou igual a zero.");
        try{String t=d.get("timestamp").getAsString();if(!t.matches("\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?([Zz]|[+-]\\d{2}:\\d{2})"))throw new IllegalArgumentException();OffsetDateTime.parse(t);}
        catch(RuntimeException e){throw new IllegalArgumentException("timestamp deve ser RFC 3339 com segundos e fuso.");}
        return d;
    }
    private synchronized void expirar() throws IOException {
        for(var item:new ArrayList<>(grupos.entrySet()))if(System.currentTimeMillis()-item.getValue().get("aberto_em").getAsLong()>=3000)fechar(item.getKey(),item.getValue());
    }
    private void fechar(String ponto,JsonObject grupo) throws IOException {
        JsonObject dados=new JsonObject();dados.addProperty("sensor",ponto);dados.addProperty("ponto_id",ponto);dados.addProperty("timestamp",grupo.get("maior").getAsString());
        Map<String,String> campos=Map.of("nivel_corrego","nivel_corrego_cm","chuva","chuva_mm","velocidade_agua","velocidade_agua_m_s");
        for(String campo:campos.values())dados.add(campo,JsonNull.INSTANCE);
        for(JsonElement e:grupo.getAsJsonArray("medicoes")){JsonObject d=e.getAsJsonObject().getAsJsonObject("leitura");dados.add(campos.get(d.get("tipo").getAsString()),d.get("valor"));}
        JsonArray ausentes=new JsonArray();for(var campo:campos.entrySet())if(dados.get(campo.getValue()).isJsonNull())ausentes.add(campo.getKey());
        dados.add("sensores_ausentes",ausentes);dados.addProperty("status",ausentes.isEmpty()?"completa":"incompleta");
        JsonObject evento=evento("leitura",dados);evento.add("evento_id",grupo.get("evento_id"));evento.add("medicoes",grupo.get("medicoes").deepCopy());
        fila.adicionar(evento);
        Files.deleteIfExists(gruposPasta.resolve(grupo.get("evento_id").getAsString()+".json"));grupos.remove(ponto);
        publicar(evento);Log.info("Conjunto "+dados.get("status").getAsString()+": ponto="+ponto+" | ausentes="+ausentes);
    }
    void gravarPendentes() {
        if(encerrado)return;
        try {
            for(Path arquivo:fila.pendentes()) {
                if(encerrado)return;
                JsonObject evento=FilaPersistente.ler(arquivo);banco.persistir(evento);Files.deleteIfExists(arquivo);
                Log.info("Gravacao concluida: "+evento.get("evento_id").getAsString());
            }
        } catch(Exception e) {
            if(System.currentTimeMillis()-ultimoErro>5000){ultimoErro=System.currentTimeMillis();Log.info("Banco indisponivel ou gravacao pendente: fila preservada; nova tentativa automatica.");}
        }
    }
    synchronized boolean disponivel(){try{return !encerrado&&fila.tamanho()+grupos.size()<limite;}catch(IOException e){return false;}}
    @Override public void close() {
        encerrado=true;agrupador.shutdownNow();gravador.shutdownNow();sse.close();
        try{gravador.awaitTermination(7,TimeUnit.SECONDS);agrupador.awaitTermination(2,TimeUnit.SECONDS);trava.release();canal.close();}
        catch(Exception e){Log.info("Encerramento: fila permanece em disco para recuperacao.");}
    }
}
