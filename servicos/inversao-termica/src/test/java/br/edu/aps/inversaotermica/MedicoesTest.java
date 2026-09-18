package br.edu.aps.inversaotermica;

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
    private static final String VALIDO = "{\"estacao\":\"teste\",\"timestamp\":\"2026-09-11T12:00:00Z\",\"umidade\":42.5,\"temperatura_c\":24.5,\"vento_km_h\":8.2}";

    @Test void validarNovasMedicoesPorHttpSemSalvarInvalidos() throws Exception {
        Path arquivo = temporario.resolve("validacao.db");
        var servidor = Api.criarServidor(0, arquivo);
        servidor.start();
        try {
            var uri = URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + "/leituras");
            var cliente = HttpClient.newHttpClient();
            String[] campos = {"temperatura_c", "vento_km_h"};
            double[] minimos = {-273.15, 0};
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
                 var r = s.executeQuery("SELECT count(*) FROM leituras_inversaotermica")) {
                assertTrue(r.next());
                assertEquals(4, r.getInt(1));
            }
        } finally { servidor.stop(0); }
    }

    @Test void migrarBancoAntigoSemPerderDadosERepetirInicializacao() throws Exception {
        Path arquivo = temporario.resolve("antigo.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + arquivo); var s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE leituras_inversaotermica (id INTEGER PRIMARY KEY AUTOINCREMENT, estacao TEXT NOT NULL, timestamp TEXT NOT NULL, umidade REAL NOT NULL, recebido_em TEXT NOT NULL DEFAULT 'original')");
            s.executeUpdate("INSERT INTO leituras_inversaotermica (estacao,timestamp,umidade) VALUES ('antigo','2026-09-11T12:00:00Z',10)");
        }
        new Banco(arquivo);
        Banco banco = new Banco(arquivo);
        banco.salvar(Leitura.deJson(VALIDO));
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + arquivo); var s = c.createStatement();
             var r = s.executeQuery("SELECT * FROM leituras_inversaotermica ORDER BY id")) {
            assertTrue(r.next());
            assertEquals(1, r.getLong("id"));
            assertEquals("antigo", r.getString("estacao"));
            assertEquals(10, r.getDouble("umidade"));
            assertEquals("original", r.getString("recebido_em"));
            assertNull(r.getObject("temperatura_c"));
            assertNull(r.getObject("vento_km_h"));
            assertTrue(r.next());
            assertEquals(2, r.getLong("id"));
            assertEquals(24.5, r.getDouble("temperatura_c"));
            assertEquals(8.2, r.getDouble("vento_km_h"));
            assertFalse(r.next());
        }
    }
}