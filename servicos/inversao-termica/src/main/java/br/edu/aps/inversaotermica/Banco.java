package br.edu.aps.inversaotermica;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Abre conexoes curtas; cada INSERT e confirmado antes de responder ao cliente. */
public final class Banco {
    private final String url;
    private final Path arquivo;

    public static Path caminhoPadrao() {
        String configurado = System.getProperty("aps.db", System.getenv("APS_DB"));
        if (configurado != null) return Path.of(configurado);
        Path pasta = Path.of("").toAbsolutePath().normalize();
        while (pasta != null) {
            if (Files.isRegularFile(pasta.resolve("pom.xml"))
                    && Files.isDirectory(pasta.resolve("servicos"))) {
                return pasta.resolve("dados").resolve("aps.db");
            }
            pasta = pasta.getParent();
        }
        throw new IllegalStateException("Execute na pasta do projeto APS ou configure -Daps.db=caminho/aps.db");
    }

    public Banco(Path arquivo) throws IOException {
        Path absoluto = arquivo.toAbsolutePath().normalize();
        this.arquivo = absoluto;
        Files.createDirectories(absoluto.getParent());
        url = "jdbc:sqlite:" + absoluto;
        try (Connection conexao = conectar(); var comando = conexao.createStatement()) {
            comando.execute("BEGIN IMMEDIATE");
            comando.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS leituras_inversaotermica (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        estacao TEXT NOT NULL,
                        timestamp TEXT NOT NULL,
                        umidade REAL NOT NULL CHECK (umidade BETWEEN 0 AND 100),
                        recebido_em TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
                    )
                    """);
            // Leituras anteriores mantêm NULL nos campos que ainda não eram enviados.
            adicionarColuna(conexao, "temperatura_c", "REAL CHECK (temperatura_c >= -273.15)");
            adicionarColuna(conexao, "vento_km_h", "REAL CHECK (vento_km_h >= 0)");
            comando.execute("COMMIT");
        } catch (SQLException e) {
            throw new IOException("Nao foi possivel inicializar o SQLite em " + absoluto, e);
        }
        System.out.println("Banco SQLite: " + absoluto);
    }

    private static void adicionarColuna(Connection conexao, String nome, String definicao) throws SQLException {
        boolean existe = false;
        try (var comando = conexao.createStatement();
             var colunas = comando.executeQuery("PRAGMA table_info(leituras_inversaotermica)")) {
            while (colunas.next()) {
                if (nome.equals(colunas.getString("name"))) existe = true;
            }
        }
        if (!existe) {
            // Nome e definição vêm exclusivamente das constantes internas acima.
            try (var comando = conexao.createStatement()) {
                comando.executeUpdate("ALTER TABLE leituras_inversaotermica ADD COLUMN " + nome + " " + definicao);
            }
        }
    }

    private Connection conectar() throws SQLException {
        java.util.Properties propriedades = new java.util.Properties();
        propriedades.setProperty("busy_timeout", "5000");
        return DriverManager.getConnection(url, propriedades);
    }

    /** Testa o esquema e a disponibilidade para escrita, sem inserir dados. */
    public boolean pronto() {
        if (!Files.isRegularFile(arquivo)) return false;
        java.util.Properties propriedades = new java.util.Properties();
        propriedades.setProperty("busy_timeout", "500");
        try (Connection conexao = DriverManager.getConnection(url, propriedades);
             var comando = conexao.createStatement()) {
            comando.execute("BEGIN IMMEDIATE");
            try (var resultado = comando.executeQuery("SELECT estacao, timestamp, umidade, temperatura_c, vento_km_h FROM leituras_inversaotermica LIMIT 0")) {
                // A consulta valida todas as colunas usadas ao receber uma leitura.
            }
            comando.execute("ROLLBACK");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public void salvar(Leitura leitura) throws SQLException {
        leitura.validar();
        try (Connection conexao = conectar(); var comando = conexao.prepareStatement(
                "INSERT INTO leituras_inversaotermica (estacao, timestamp, umidade, temperatura_c, vento_km_h) VALUES (?, ?, ?, ?, ?)")) {
            comando.setString(1, leitura.estacao());
            comando.setString(2, leitura.timestamp());
            comando.setDouble(3, leitura.umidade());
            comando.setDouble(4, leitura.temperatura_c());
            comando.setDouble(5, leitura.vento_km_h());
            comando.executeUpdate();
        }
    }
}