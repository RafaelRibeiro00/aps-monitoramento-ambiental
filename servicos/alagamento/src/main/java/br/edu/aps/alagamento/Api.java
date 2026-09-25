package br.edu.aps.alagamento;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

public class Api {
    public static final int PORTA=Integer.parseInt(Configuracao.valor("APS_PORT",Configuracao.valor("ALAGAMENTO_PORT","8082")));
    private static final String HOST=Configuracao.valor("APS_HOST","127.0.0.1");
    private static final String PREFIXO="/alagamento";
    public static void main(String[] args) throws IOException {
        Path arquivo=Banco.caminhoPadrao();
        if(args.length>0 && "--migrar-banco".equals(args[0])) {new Banco(arquivo);Log.info("Migracao concluida. Nenhum servidor iniciado.");return;}
        HttpServer servidor=criarServidor(PORTA,arquivo);
        Notificador notificador=new Notificador(new Banco(arquivo));notificador.iniciar();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{notificador.close();servidor.stop(0);}));
        servidor.start();
        Log.info("API iniciada: http://"+HOST+":"+PORTA+PREFIXO);
        Log.info("POST /leituras | GET /historico | SSE /tempo-real | GET /alertas | GET /alertas-historico");
    }
    public static HttpServer criarServidor(int porta) throws IOException {return criarServidor(porta,Banco.caminhoPadrao());}
    public static HttpServer criarServidor(int porta,Path arquivo) throws IOException {
        Banco banco=new Banco(arquivo);Fluxo fluxo=new Fluxo(banco,arquivo);
        HttpServer servidor;
        try{servidor=HttpServer.create(new InetSocketAddress(HOST,porta),128);}catch(IOException e){fluxo.close();throw e;}
        ThreadPoolExecutor executor=new ThreadPoolExecutor(32,32,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256),r->{Thread t=new Thread(r,"http-alagamento");t.setDaemon(true);return t;});
        servidor.setExecutor(executor);servidor.createContext("/",r->receber(r,banco,fluxo));
        return new ServidorHttp(servidor,fluxo,executor);
    }
    private static void receber(HttpExchange r,Banco banco,Fluxo fluxo) throws IOException {
        try {
            String origem=r.getRequestHeaders().getFirst("Origin");
            if(origem!=null) {
                var permitidas=Arrays.asList(Configuracao.valor("APS_CORS_ORIGINS","").split(","));
                if(permitidas.stream().map(String::strip).anyMatch(origem::equals)) {
                    r.getResponseHeaders().set("Access-Control-Allow-Origin",origem);r.getResponseHeaders().set("Vary","Origin");
                } else {responder(r,403,Map.of("erro","Origem nao permitida. Configure APS_CORS_ORIGINS."));return;}
            }
            String rota=r.getRequestURI().getPath();String metodo=r.getRequestMethod();
            if("OPTIONS".equals(metodo)) {
                r.getResponseHeaders().set("Access-Control-Allow-Methods","GET, POST, OPTIONS");r.getResponseHeaders().set("Access-Control-Allow-Headers","Content-Type, Last-Event-ID");r.sendResponseHeaders(204,-1);return;
            }
            if(rota.equals("/health/live")||rota.equals("/health/ready")) {
                if(!exigir(r,"GET"))return;
                boolean pronto=rota.endsWith("live")||(banco.pronto()&&fluxo.disponivel());responder(r,pronto?200:503,Map.of("status",pronto?"ok":"indisponivel"));return;
            }
            if(!rota.startsWith(PREFIXO+"/")){responder(r,404,Map.of("erro","Rota inexistente. Use o prefixo "+PREFIXO));return;}
            rota=rota.substring(PREFIXO.length());
            switch(rota) {
                case "/leituras" -> {
                    if(!exigir(r,"POST"))return;
                    byte[] corpo=r.getRequestBody().readNBytes(4097);if(corpo.length>4096){responder(r,400,Map.of("erro","O corpo deve ter no maximo 4096 bytes."));return;}
                    responder(r,202,fluxo.receber(new String(corpo,StandardCharsets.UTF_8)));
                }
                case "/historico" -> {if(exigir(r,"GET"))responder(r,200,banco.consultar(Consulta.deQuery(r.getRequestURI().getRawQuery())));}
                case "/alertas-historico" -> {if(exigir(r,"GET"))responder(r,200,banco.consultarAlertas(Consulta.deQuery(r.getRequestURI().getRawQuery(),true)));}
                case "/alertas" -> {
                    if(!exigir(r,"GET"))return;
                    if(r.getRequestURI().getRawQuery()!=null)throw new IllegalArgumentException("Use /alertas-historico para consultar com filtros.");
                    responder(r,200,Map.of("alertas",fluxo.recentes.listar()));
                }
                case "/tempo-real" -> {if(exigir(r,"GET"))fluxo.sse.conectar(r);}
                default -> responder(r,404,Map.of("erro","Rota inexistente."));
            }
        } catch(Fluxo.Conflito e){responder(r,409,Map.of("erro",e.getMessage()));}
        catch(IllegalArgumentException e){responder(r,400,Map.of("erro",e.getMessage()==null?"Entrada invalida":e.getMessage()));}
        catch(java.sql.SQLException e){Log.info("Consulta indisponivel: banco ocupado ou inacessivel.");responder(r,503,Map.of("erro","Banco temporariamente indisponivel."));}
        catch(IOException e){Log.info("Recebimento indisponivel: "+e.getClass().getSimpleName());r.getResponseHeaders().set("Retry-After","3");responder(r,503,Map.of("erro","Nao foi possivel confirmar o recebimento. Tente novamente."));}
        finally{r.close();}
    }
    private static boolean exigir(HttpExchange r,String metodo) throws IOException {
        if(r.getRequestMethod().equals(metodo))return true;r.getResponseHeaders().set("Allow",metodo);responder(r,405,Map.of("erro","Use "+metodo+" nesta rota."));return false;
    }
    private static void responder(HttpExchange r,int status,Object dados) throws IOException {
        byte[] corpo=Fluxo.JSON.toJson(dados).getBytes(StandardCharsets.UTF_8);r.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");r.sendResponseHeaders(status,corpo.length);r.getResponseBody().write(corpo);
    }
}
