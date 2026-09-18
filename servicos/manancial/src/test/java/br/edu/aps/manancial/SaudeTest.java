package br.edu.aps.manancial;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.*;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class SaudeTest {
    @TempDir Path temporario;

    @Test void prontidaoDetectaFalhaERecuperacaoSemDerrubarLiveness() throws Exception {
        Path arquivo = temporario.resolve("saude.db");
        HttpServer servidor = Api.criarServidor(0, arquivo);
        servidor.start();
        try (var conexao = DriverManager.getConnection("jdbc:sqlite:" + arquivo);
             var comando = conexao.createStatement()) {
            assertEquals(200, consultar(servidor, "/health/live", "GET"));
            assertEquals(200, consultar(servidor, "/health/ready", "GET"));
            comando.execute("ALTER TABLE leituras_manancial RENAME TO indisponivel");
            assertEquals(503, consultar(servidor, "/health/ready", "GET"));
            assertEquals(200, consultar(servidor, "/health/live", "GET"));
            comando.execute("ALTER TABLE indisponivel RENAME TO leituras_manancial");
            assertEquals(200, consultar(servidor, "/health/ready", "GET"));
            assertEquals(405, consultar(servidor, "/health/ready", "POST"));
            assertEquals(405, consultar(servidor, "/health/live", "POST"));
            comando.execute("BEGIN IMMEDIATE");
            assertEquals(503, consultar(servidor, "/health/ready", "GET"));
            assertEquals(200, consultar(servidor, "/health/live", "GET"));
            comando.execute("ROLLBACK");
            assertEquals(200, consultar(servidor, "/health/ready", "GET"));
        } finally { servidor.stop(0); }
    }

    private int consultar(HttpServer servidor, String rota, String metodo) throws Exception {
        var pedido = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + servidor.getAddress().getPort() + rota))
                .timeout(java.time.Duration.ofSeconds(3))
                .method(metodo, HttpRequest.BodyPublishers.noBody()).build();
        return HttpClient.newHttpClient().send(pedido, HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
