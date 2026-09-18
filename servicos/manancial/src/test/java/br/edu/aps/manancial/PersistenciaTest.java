package br.edu.aps.manancial;

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
            String json = "{\"area\":\"origem ' teste\",\"timestamp\":\"2026-09-11T12:00:00-03:00\",\"percentual_ocupado\":42.5,\"nivel_agua_m\":12.3,\"temperatura_agua_c\":22.5}";
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
             var linhas = comando.executeQuery("SELECT * FROM leituras_manancial ORDER BY id")) {
            for (int i = 1; i <= 2; i++) {
                assertTrue(linhas.next());
                assertEquals(i, linhas.getLong("id"));
                assertEquals("origem ' teste", linhas.getString("area"));
                assertEquals("2026-09-11T12:00:00-03:00", linhas.getString("timestamp"));
                assertEquals(42.5, linhas.getDouble("percentual_ocupado"));
                assertEquals(12.3, linhas.getDouble("nivel_agua_m"));
                assertEquals(22.5, linhas.getDouble("temperatura_agua_c"));
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
                comando.executeUpdate("DROP TABLE leituras_manancial");
            }
            var uri = URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + "/leituras");
            var pedido = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(
                "{\"area\":\"teste\",\"timestamp\":\"2026-09-11T12:00:00Z\",\"percentual_ocupado\":42.5,\"nivel_agua_m\":12.3,\"temperatura_agua_c\":22.5}")).build();
            assertEquals(500, HttpClient.newHttpClient().send(pedido, HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally { servidor.stop(0); }
    }
}