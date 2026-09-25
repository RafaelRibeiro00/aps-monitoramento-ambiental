package br.edu.aps.alagamento;
import com.sun.net.httpserver.*;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.util.concurrent.*;
/** Fecha executores e libera a fila tambem quando os testes chamam stop(). */
final class ServidorHttp extends HttpServer {
    private final HttpServer servidor;private final Fluxo fluxo;private final ExecutorService executor;
    ServidorHttp(HttpServer servidor,Fluxo fluxo,ExecutorService executor){this.servidor=servidor;this.fluxo=fluxo;this.executor=executor;}
    public void bind(InetSocketAddress endereco,int backlog)throws IOException{servidor.bind(endereco,backlog);}
    public void start(){servidor.start();}
    public void setExecutor(Executor e){servidor.setExecutor(e);}
    public Executor getExecutor(){return servidor.getExecutor();}
    public void stop(int atraso){servidor.stop(atraso);executor.shutdownNow();fluxo.close();}
    public HttpContext createContext(String caminho,HttpHandler h){return servidor.createContext(caminho,h);}
    public HttpContext createContext(String caminho){return servidor.createContext(caminho);}
    public void removeContext(String caminho){servidor.removeContext(caminho);}
    public void removeContext(HttpContext contexto){servidor.removeContext(contexto);}
    public InetSocketAddress getAddress(){return servidor.getAddress();}
}
