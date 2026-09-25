package br.edu.aps.inversaotermica;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.*;
import java.nio.file.*;
import java.sql.*;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
class RuntimeTest {
 @TempDir Path pasta;
 static final String CORPO="{\"estacao\":\"PONTO-TESTE\",\"timestamp\":\"2026-09-24T15:00:00Z\",\"umidade\":20,\"temperatura_c\":32,\"vento_km_h\":1}";
 static void aguardar(BooleanSupplier condicao) throws Exception {
  long prazo=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
  while(!condicao.getAsBoolean()&&System.nanoTime()<prazo)Thread.sleep(25);
  assertTrue(condicao.getAsBoolean(),"Operacao assincrona nao concluiu no prazo");
 }
 static long contar(Path arquivo,String tabela) {
  try(var c=DriverManager.getConnection("jdbc:sqlite:"+arquivo);var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM "+tabela)){return r.getLong(1);}catch(SQLException e){return -1;}
 }
 static HttpResponse<String> pedido(String base,String rota,String corpo) throws Exception {
  var p=HttpRequest.newBuilder(URI.create(base+rota)).timeout(Duration.ofSeconds(8));
  if(corpo==null)p.GET();else p.POST(HttpRequest.BodyPublishers.ofString(corpo));
  return HttpClient.newHttpClient().send(p.build(),HttpResponse.BodyHandlers.ofString());
 }
 @Test void sseEAlertasContinuamComBancoBloqueado() throws Exception {
  Path arquivo=pasta.resolve("independente.db");var servidor=Api.criarServidor(0,arquivo);servidor.start();
  String base="http://127.0.0.1:"+servidor.getAddress().getPort()+"/inversao-termica";
  try(var c=DriverManager.getConnection("jdbc:sqlite:"+arquivo);var sql=c.createStatement()) {
   sql.execute("BEGIN IMMEDIATE");
   var resposta=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base+"/tempo-real")).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
   assertEquals(200,resposta.statusCode());
   try(var stream=resposta.body()) {
    var leitura=CompletableFuture.supplyAsync(()->{try{var r=new BufferedReader(new InputStreamReader(stream));String linha;while((linha=r.readLine())!=null)if(linha.startsWith("data: "))return JsonParser.parseString(linha.substring(6)).getAsJsonObject();return null;}catch(IOException e){throw new RuntimeException(e);}});
    long inicio=System.nanoTime();var aceite=pedido(base,"/leituras",CORPO);assertEquals(202,aceite.statusCode(),aceite.body());
    assertTrue(System.nanoTime()-inicio<TimeUnit.SECONDS.toNanos(2),"POST nao deve esperar o SQLite");
    var recentes=pedido(base,"/alertas",null);assertEquals(1,JsonParser.parseString(recentes.body()).getAsJsonObject().getAsJsonArray("alertas").size());
    JsonObject evento=leitura.get(5,TimeUnit.SECONDS);assertNotNull(evento);assertTrue(evento.has("evento_id"));
    assertEquals(0,contar(arquivo,"leituras_inversaotermica"));
    sql.execute("ROLLBACK");
    aguardar(()->contar(arquivo,"leituras_inversaotermica")==1);
    var historico=pedido(base,"/alertas-historico?limite=1&ordem=desc",null);
    assertEquals(1,JsonParser.parseString(historico.body()).getAsJsonObject().get("total").getAsInt());
   }
  }finally{servidor.stop(0);}
 }
 @Test void alertasExpiramIndividualmenteSemRenovarConsulta() {
  class Relogio extends Clock {Instant agora=Instant.parse("2026-09-24T00:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return agora;}}
  Relogio clock=new Relogio();AlertasRecentes cache=new AlertasRecentes(clock);
  JsonObject a=new JsonObject();a.addProperty("evento_id","primeiro");a.addProperty("criado_em",clock.instant().toString());cache.adicionar(a);
  clock.agora=clock.agora.plusSeconds(20);a.addProperty("evento_id","segundo");a.addProperty("criado_em",clock.instant().toString());cache.adicionar(a);
  assertEquals(2,cache.listar().size());clock.agora=clock.agora.plusSeconds(40);
  assertEquals(1,cache.listar().size());assertEquals("segundo",cache.listar().get(0).get("evento_id").getAsString());
  clock.agora=clock.agora.plusSeconds(20);assertTrue(cache.listar().isEmpty());
 }
 @Test void processamentoDeFilaEhIdempotente() throws Exception {
  Path arquivo=pasta.resolve("idempotente.db");Banco banco=new Banco(arquivo);
  try(var c=DriverManager.getConnection("jdbc:sqlite:"+arquivo);var sql=c.createStatement();Fluxo fluxo=new Fluxo(banco,arquivo)) {
   sql.execute("BEGIN IMMEDIATE");fluxo.receber(CORPO);
   Path pendente=fluxo.fila.pendentes().get(0);JsonObject evento=FilaPersistente.ler(pendente);
   sql.execute("ROLLBACK");banco.persistir(evento);banco.persistir(evento);
   aguardar(()->contar(arquivo,"leituras_inversaotermica")==1);
   assertEquals(1,contar(arquivo,"alertas_inversaotermica"));
  }
 }
 @Test void rejeitaSobrecargaSemAceitarSilenciosamente() throws Exception {
  System.setProperty("aps.fila.limite","1");
  Path arquivo=pasta.resolve("cheia.db");var servidor=Api.criarServidor(0,arquivo);servidor.start();
  String base="http://127.0.0.1:"+servidor.getAddress().getPort()+"/inversao-termica";
  try(var c=DriverManager.getConnection("jdbc:sqlite:"+arquivo);var sql=c.createStatement()) {
   sql.execute("BEGIN IMMEDIATE");assertEquals(202,pedido(base,"/leituras",CORPO).statusCode());assertEquals(503,pedido(base,"/leituras",CORPO).statusCode());sql.execute("ROLLBACK");
  }finally{servidor.stop(0);System.clearProperty("aps.fila.limite");}
 }
}
