package br.edu.aps.inversaotermica;
import com.google.gson.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
final class FilaPersistente {
    static final Gson JSON = new GsonBuilder().serializeNulls().create();
    final Path pasta;
    FilaPersistente(Path pasta) throws IOException { this.pasta=pasta; Files.createDirectories(pasta); }
    synchronized int tamanho() throws IOException { try(var arquivos=Files.list(pasta)) { return (int)arquivos.filter(p->p.toString().endsWith(".json")).count(); } }
    synchronized void adicionar(JsonObject evento) throws IOException {
        Path destino=pasta.resolve(evento.get("evento_id").getAsString()+".json");
        if (Files.exists(destino)) return;
        escrever(destino, evento);
    }
    static void escrever(Path destino, JsonObject valor) throws IOException {
        Path temporario=destino.resolveSibling(destino.getFileName()+".tmp");
        byte[] dados=JSON.toJson(valor).getBytes(StandardCharsets.UTF_8);
        try(var canal=FileChannel.open(temporario, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer=ByteBuffer.wrap(dados); while(buffer.hasRemaining()) canal.write(buffer); canal.force(true);
        }
        try { Files.move(temporario,destino,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        catch(AtomicMoveNotSupportedException e) { Files.move(temporario,destino,StandardCopyOption.REPLACE_EXISTING); }
    }
    synchronized List<Path> pendentes() throws IOException {
        try(var arquivos=Files.list(pasta)) { return arquivos.filter(p->p.toString().endsWith(".json")).sorted().limit(32).toList(); }
    }
    static JsonObject ler(Path arquivo) throws IOException { return JsonParser.parseString(Files.readString(arquivo)).getAsJsonObject(); }
}
