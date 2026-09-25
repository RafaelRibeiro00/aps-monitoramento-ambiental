package br.edu.aps.manancial;

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
    private static final String VALIDO = "{\"area\":\"teste\",\"timestamp\":\"2026-09-11T12:00:00Z\",\"percentual_ocupado\":42.5,\"nivel_agua_m\":12.3,\"temperatura_agua_c\":22.5}";

    @Test void validarNovasMedicoesPorHttpSemSalvarInvalidos() throws Exception {
        Path arquivo = temporario.resolve("validacao.db");
        var servidor = Api.criarServidor(0, arquivo);
        servidor.start();
        try {
            var uri = URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + "/manancial/leituras");
            var cliente = HttpClient.newHttpClient();
            String[] campos = {"nivel_agua_m", "temperatura_agua_c"};
            double[] minimos = {0, -273.15};
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
                    assertEquals(202, cliente.send(pedido, HttpResponse.BodyHandlers.ofString()).statusCode());
                }
            }
            RuntimeTest.aguardar(() -> RuntimeTest.contar(arquivo, "leituras_manancial") == 4);
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + arquivo); var s = c.createStatement();
                 var r = s.executeQuery("SELECT count(*) FROM leituras_manancial")) {
                assertTrue(r.next());
                assertEquals(4, r.getInt(1));
            }
        } finally { servidor.stop(0); }
    }

    @Test void migrarBancoAntigoSemPerderDadosERepetirInicializacao() throws Exception {
        Path arquivo = temporario.resolve("antigo.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + arquivo); var s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE leituras_manancial (id INTEGER PRIMARY KEY AUTOINCREMENT, area TEXT NOT NULL, timestamp TEXT NOT NULL, percentual_ocupado REAL NOT NULL, recebido_em TEXT NOT NULL DEFAULT 'original')");
            s.executeUpdate("INSERT INTO leituras_manancial (area,timestamp,percentual_ocupado) VALUES ('antigo','2026-09-11T12:00:00Z',10)");
        }
        new Banco(arquivo);
        Banco banco = new Banco(arquivo);
        banco.salvar(Leitura.deJson(VALIDO));
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + arquivo); var s = c.createStatement();
             var r = s.executeQuery("SELECT * FROM leituras_manancial ORDER BY id")) {
            assertTrue(r.next());
            assertEquals(1, r.getLong("id"));
            assertEquals("antigo", r.getString("area"));
            assertEquals(10, r.getDouble("percentual_ocupado"));
            assertEquals("original", r.getString("recebido_em"));
            assertNull(r.getObject("nivel_agua_m"));
            assertNull(r.getObject("temperatura_agua_c"));
            assertTrue(r.next());
            assertEquals(2, r.getLong("id"));
            assertEquals(12.3, r.getDouble("nivel_agua_m"));
            assertEquals(22.5, r.getDouble("temperatura_agua_c"));
            assertFalse(r.next());
        }
    }
}