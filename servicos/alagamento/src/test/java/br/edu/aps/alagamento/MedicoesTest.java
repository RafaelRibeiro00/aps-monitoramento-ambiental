package br.edu.aps.alagamento;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

class MedicoesTest {
    @TempDir Path temporario;
    private static final String VALIDO = "{\"sensor\":\"teste\",\"timestamp\":\"2026-09-11T12:00:00Z\",\"nivel_corrego_cm\":42.5,\"chuva_mm\":15.2,\"velocidade_agua_m_s\":1.5}";

    @Test void validarNovasMedicoesPorHttpSemSalvarInvalidos() throws Exception {
        Path arquivo = temporario.resolve("validacao.db");
        var servidor = Api.criarServidor(0, arquivo);
        servidor.start();
        try {
            var uri = URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + "/leituras");
            var cliente = HttpClient.newHttpClient();
            String[] campos = {"chuva_mm", "velocidade_agua_m_s"};
            double[] minimos = {0, 0};
            for (int i = 0; i < campos.length; i++) {
                for (String valor : new String[]{"ausente", "null", "\"12\"", "true", "{}", "[]", "1e999", Double.toString(minimos[i] - 1)}) {
                    var json = JsonParser.parseString(VALIDO).getAsJsonObject();
                    if (valor.equals("ausente")) json.remove(campos[i]);
                    else json.add(campos[i], JsonParser.parseString(valor));
                    var pedido = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(json.toString())).build();
                    var resposta = cliente.send(pedido, HttpResponse.BodyHandlers.ofString());
                    assertEquals(400, resposta.statusCode(), campos[i] + ": " + valor + " " + resposta.body());
                }
                for (double valor : new double[]{0, minimos[i]}) {
                    var json = JsonParser.parseString(VALIDO).getAsJsonObject();
                    json.addProperty(campos[i], valor);
                    var pedido = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(json.toString())).build();
                    assertEquals(200, cliente.send(pedido, HttpResponse.BodyHandlers.ofString()).statusCode());
                }
            }
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + arquivo); var s = c.createStatement();
                 var r = s.executeQuery("SELECT count(*) FROM leituras_alagamento")) {
                assertTrue(r.next());
                assertEquals(4, r.getInt(1));
            }
        } finally { servidor.stop(0); }
    }

    @Test void migrarBancoAntigoSemPerderDadosERepetirInicializacao() throws Exception {
        Path arquivo = temporario.resolve("antigo.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + arquivo); var s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE leituras_alagamento (id INTEGER PRIMARY KEY AUTOINCREMENT, sensor TEXT NOT NULL, timestamp TEXT NOT NULL, nivel_corrego_cm REAL NOT NULL, recebido_em TEXT NOT NULL DEFAULT 'original')");
            s.executeUpdate("INSERT INTO leituras_alagamento (sensor,timestamp,nivel_corrego_cm) VALUES ('antigo','2026-09-11T12:00:00Z',10)");
        }
        new Banco(arquivo);
        Banco banco = new Banco(arquivo);
        banco.salvar(Leitura.deJson(VALIDO));
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + arquivo); var s = c.createStatement();
             var r = s.executeQuery("SELECT * FROM leituras_alagamento ORDER BY id")) {
            assertTrue(r.next());
            assertEquals(1, r.getLong("id"));
            assertEquals("antigo", r.getString("sensor"));
            assertEquals(10, r.getDouble("nivel_corrego_cm"));
            assertEquals("original", r.getString("recebido_em"));
            assertNull(r.getObject("chuva_mm"));
            assertNull(r.getObject("velocidade_agua_m_s"));
            assertTrue(r.next());
            assertEquals(2, r.getLong("id"));
            assertEquals(15.2, r.getDouble("chuva_mm"));
            assertEquals(1.5, r.getDouble("velocidade_agua_m_s"));
            assertFalse(r.next());
        }
    }
}