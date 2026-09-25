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