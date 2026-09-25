package br.edu.aps.alagamento;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.*;
final class TempoReal implements AutoCloseable {
    private final Set<Cliente> clientes=ConcurrentHashMap.newKeySet();
    private final Semaphore vagas=new Semaphore(16);
    private volatile boolean aberto=true;
    private static class Cliente {
        final ArrayBlockingQueue<String> fila=new ArrayBlockingQueue<>(64);
        final HttpExchange troca;
        volatile boolean ativo=true;
        Cliente(HttpExchange troca) { this.troca=troca; }
    }
    void publicar(String id, String json) {
        String evento="id: "+id+"\nevent: leitura\ndata: "+json+"\n\n";
        for(Cliente c:clientes) if(c.ativo && !c.fila.offer(evento)) {
            c.ativo=false;
            Log.info("SSE: cliente lento desconectado; consulte o historico para recuperar leituras.");
        }
    }
    void conectar(HttpExchange troca) throws IOException {
        if(!aberto || !vagas.tryAcquire()) {
            troca.getResponseHeaders().set("Retry-After","5");
            troca.sendResponseHeaders(503,-1); troca.close(); return;
        }
        Cliente cliente=new Cliente(troca);clientes.add(cliente);
        try {
            troca.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");
            troca.getResponseHeaders().set("Cache-Control","no-cache");
            troca.getResponseHeaders().set("X-Accel-Buffering","no");
            troca.sendResponseHeaders(200,0);
            OutputStream saida=troca.getResponseBody();
            saida.write("retry: 3000\n: conectado; recupere interrupcoes em /historico\n\n".getBytes(StandardCharsets.UTF_8));saida.flush();
            Log.info("SSE conectado: "+clientes.size()+" cliente(s).");
            while(aberto && cliente.ativo) {
                String evento=cliente.fila.poll(10,TimeUnit.SECONDS);
                saida.write((evento==null?": ativo\n\n":evento).getBytes(StandardCharsets.UTF_8));saida.flush();
            }
        } catch(InterruptedException e) {Thread.currentThread().interrupt();}
        catch(IOException e) { /* Cliente encerrou ou nao consegue receber. */ }
        finally {clientes.remove(cliente);vagas.release();troca.close();Log.info("SSE desconectado.");}
    }
    @Override public void close() { aberto=false;for(Cliente c:clientes){c.ativo=false;c.troca.close();} }
}
