package br.edu.aps.inversaotermica;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Atendimento sem estado; o coordenador preserva agrupamento, fila, banco e SSE. */
final class Replica {
    private static final HttpClient CLIENTE=HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3)).version(HttpClient.Version.HTTP_1_1).build();
    static void iniciar(int porta) throws IOException {
        String destino=Configuracao.valor("APS_COORDENADOR","");
        HttpServer servidor=criar(porta,destino);
        Runtime.getRuntime().addShutdownHook(new Thread(()->servidor.stop(10)));
        servidor.start();
        Log.info("Replica HTTP pronta: porta="+porta+" coordenador="+destino);
    }
    static HttpServer criar(int porta,String destino) throws IOException {
        URI base=URI.create(destino);
        if(!"http".equals(base.getScheme()) || base.getHost()==null || base.getRawQuery()!=null)
            throw new IllegalArgumentException("Coordenador deve ser uma URL HTTP interna fixa");
        HttpServer server=HttpServer.create(new InetSocketAddress(Configuracao.valor("APS_HOST","127.0.0.1"),porta),128);
        ExecutorService executor=Executors.newFixedThreadPool(32,r->{Thread t=new Thread(r,"replica-http");t.setDaemon(true);return t;});
        server.setExecutor(executor);
        server.createContext("/",r->{
            boolean iniciou=false;
            try {
                r.getResponseHeaders().set("X-Pod",Configuracao.valor("POD_NAME","replica-local"));
                r.getResponseHeaders().set("X-Versao",Configuracao.valor("APS_VERSAO","3.0.0"));
                String path=r.getRequestURI().getRawPath();
                if(path.equals("/health/live")) {r.sendResponseHeaders(200,2);r.getResponseBody().write("{}".getBytes());return;}
                if(!path.startsWith("/inversao-termica/") && !path.equals("/health/ready")) {r.sendResponseHeaders(404,-1);return;}
                byte[] body=r.getRequestBody().readNBytes(4097);
                if(body.length>4096) {r.sendResponseHeaders(400,-1);return;}
                if(path.equals("/inversao-termica/leituras") && r.getRequestMethod().equals("POST")) {
                    String json=new String(body,StandardCharsets.UTF_8);
                    Leitura.deJson(json);
                }
                String query=r.getRequestURI().getRawQuery();
                URI target=URI.create(destino+path+(query==null?"":"?"+query));
                var request=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(path.endsWith("/tempo-real")?86400:15))
                    .method(r.getRequestMethod(),body.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
                for(String h:List.of("Content-Type","Origin","Accept","Last-Event-ID")) {
                    String v=r.getRequestHeaders().getFirst(h);if(v!=null)request.header(h,v);
                }
                var response=CLIENTE.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
                try(InputStream in=response.body()) {
                    for(String h:List.of("Content-Type","Cache-Control","X-Accel-Buffering","Access-Control-Allow-Origin","Access-Control-Allow-Methods","Access-Control-Allow-Headers","Vary","Retry-After","Allow"))
                        response.headers().firstValue(h).ifPresent(v->r.getResponseHeaders().set(h,v));
                    response.headers().firstValue("X-Pod").ifPresent(v->r.getResponseHeaders().set("X-Backend-Pod",v));
                    r.getResponseHeaders().set("Access-Control-Expose-Headers","X-Pod, X-Versao, X-Backend-Pod");
                    r.sendResponseHeaders(response.statusCode(),response.statusCode()==204?-1:0);iniciou=true;
                    byte[] buffer=new byte[8192];int n;
                    while((n=in.read(buffer))!=-1){r.getResponseBody().write(buffer,0,n);r.getResponseBody().flush();}
                }
            } catch(IllegalArgumentException e) {if(!iniciou)r.sendResponseHeaders(400,-1);}
            catch(InterruptedException e) {Thread.currentThread().interrupt();if(!iniciou)r.sendResponseHeaders(503,-1);}
            catch(IOException e) {if(!iniciou)r.sendResponseHeaders(503,-1);}
            finally {r.close();}
        });
        return new HttpServer() {
            public void bind(InetSocketAddress a,int b)throws IOException{server.bind(a,b);}
            public void start(){server.start();}
            public void stop(int delay){server.stop(delay);executor.shutdownNow();}
            public void setExecutor(Executor e){server.setExecutor(e);}
            public Executor getExecutor(){return server.getExecutor();}
            public HttpContext createContext(String p,HttpHandler h){return server.createContext(p,h);}
            public HttpContext createContext(String p){return server.createContext(p);}
            public void removeContext(String p){server.removeContext(p);}
            public void removeContext(HttpContext c){server.removeContext(c);}
            public InetSocketAddress getAddress(){return server.getAddress();}
        };
    }
}
