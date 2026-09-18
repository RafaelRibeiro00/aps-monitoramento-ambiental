package br.edu.aps.inversaotermica;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

class PersistenciaTest {
    @TempDir Path temporario;

    @Test void persistirPorHttpEReiniciar() throws Exception {
        Path arquivo = temporario.resolve("aps.db");
        var servidor = Api.criarServidor(0, arquivo);
        servidor.start();
        try {
            var uri = URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + "/leituras");
            String json = "{\"estacao\":\"origem ' teste\",\"timestamp\":\"2026-09-11T12:00:00-03:00\",\"umidade\":42.5,\"temperatura_c\":24.5,\"vento_km_h\":8.2}";
            var cliente = HttpClient.newHttpClient();
            for (int i = 0; i < 2; i++) {
                var pedido = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(json)).build();
                assertEquals(200, cliente.send(pedido, HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            var invalido = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(json.replace("42.5", "-1"))).build();
            assertEquals(400, cliente.send(invalido, HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally { servidor.stop(0); }
        var reiniciado = Api.criarServidor(0, arquivo);
        reiniciado.start();
        try (var conexao = DriverManager.getConnection("jdbc:sqlite:" + arquivo);
             var comando = conexao.createStatement();
             var linhas = comando.executeQuery("SELECT * FROM leituras_inversaotermica ORDER BY id")) {
            for (int i = 1; i <= 2; i++) {
                assertTrue(linhas.next());
                assertEquals(i, linhas.getLong("id"));
                assertEquals("origem ' teste", linhas.getString("estacao"));
                assertEquals("2026-09-11T12:00:00-03:00", linhas.getString("timestamp"));
                assertEquals(42.5, linhas.getDouble("umidade"));
                assertEquals(24.5, linhas.getDouble("temperatura_c"));
                assertEquals(8.2, linhas.getDouble("vento_km_h"));
                assertNotNull(linhas.getString("recebido_em"));
            }
            assertFalse(linhas.next());
        } finally { reiniciado.stop(0); }
    }

    @Test void falhaDeGravacaoRetorna500() throws Exception {
        Path arquivo = temporario.resolve("falha.db");
        var servidor = Api.criarServidor(0, arquivo);
        servidor.start();
        try {
            try (var conexao = DriverManager.getConnection("jdbc:sqlite:" + arquivo);
                 var comando = conexao.createStatement()) {
                comando.executeUpdate("DROP TABLE leituras_inversaotermica");
            }
            var uri = URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + "/leituras");
            var pedido = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(
                "{\"estacao\":\"teste\",\"timestamp\":\"2026-09-11T12:00:00Z\",\"umidade\":42.5,\"temperatura_c\":24.5,\"vento_km_h\":8.2}")).build();
            assertEquals(500, HttpClient.newHttpClient().send(pedido, HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally { servidor.stop(0); }
    }
}