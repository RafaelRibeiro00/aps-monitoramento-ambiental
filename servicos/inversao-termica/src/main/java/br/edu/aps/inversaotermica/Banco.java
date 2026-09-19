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
            comando.executeUpdate("""
                CREATE TABLE IF NOT EXISTS alertas_inversaotermica (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    leitura_id INTEGER NOT NULL UNIQUE REFERENCES leituras_inversaotermica(id) ON DELETE CASCADE,
                    tipo TEXT NOT NULL,
                    mensagem TEXT NOT NULL,
                    detalhes TEXT NOT NULL,
                    criado_em TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    status_notificacao TEXT NOT NULL DEFAULT 'pendente' CHECK(status_notificacao IN ('pendente', 'enviado')),
                    tentativas INTEGER NOT NULL DEFAULT 0,
                    proxima_tentativa INTEGER NOT NULL DEFAULT 0,
                    enviado_em TEXT,
                    ultimo_erro TEXT
                )
                """);
            comando.executeUpdate("CREATE INDEX IF NOT EXISTS idx_alertas_inversaotermica_envio ON alertas_inversaotermica(status_notificacao, proxima_tentativa)");
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
        propriedades.setProperty("foreign_keys", "true");
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
            try (var resultado = comando.executeQuery("SELECT id, leitura_id, detalhes, status_notificacao FROM alertas_inversaotermica LIMIT 0")) {
                // A fila de alertas tambem precisa estar disponivel.
            }
            comando.execute("ROLLBACK");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public java.util.Map<String, Object> consultar(Consulta consulta) throws SQLException {
        try (Connection conexao = conectar()) {
            // Contagem e pagina usam a mesma fotografia do banco.
            conexao.setAutoCommit(false);
            long total;
            try (var comando = conexao.prepareStatement("SELECT count(*) FROM leituras_inversaotermica" + consulta.where())) {
                preencher(comando, consulta.valores());
                try (var resultado = comando.executeQuery()) { total = resultado.getLong(1); }
            }
            var leituras = new java.util.ArrayList<java.util.Map<String, Object>>();
            try (var comando = conexao.prepareStatement("SELECT * FROM leituras_inversaotermica" + consulta.where() + " ORDER BY id ASC LIMIT ? OFFSET ?")) {
                preencher(comando, consulta.valores());
                comando.setInt(consulta.valores().size() + 1, consulta.limite());
                comando.setInt(consulta.valores().size() + 2, consulta.offset());
                try (var resultado = comando.executeQuery()) {
                    var colunas = resultado.getMetaData();
                    while (resultado.next()) {
                        var linha = new java.util.LinkedHashMap<String, Object>();
                        for (int i = 1; i <= colunas.getColumnCount(); i++)
                            linha.put(colunas.getColumnName(i), resultado.getObject(i));
                        leituras.add(linha);
                    }
                }
            }
            conexao.commit();
            return java.util.Map.of("total", total, "limite", consulta.limite(), "offset", consulta.offset(), "leituras", leituras);
        }
    }

    private static void preencher(java.sql.PreparedStatement comando, java.util.List<Object> valores) throws SQLException {
        for (int i = 0; i < valores.size(); i++) comando.setObject(i + 1, valores.get(i));
    }

    public java.util.Map<String, Object> salvar(Leitura leitura) throws SQLException {
        leitura.validar();
        var regra = RegrasAlerta.avaliar(leitura);
        var alertas = new java.util.ArrayList<java.util.Map<String, Object>>();
        try (Connection conexao = conectar()) {
            conexao.setAutoCommit(false);
            try (var comando = conexao.prepareStatement("INSERT INTO leituras_inversaotermica (estacao, timestamp, umidade, temperatura_c, vento_km_h) VALUES (?, ?, ?, ?, ?)")) {
                comando.setString(1, leitura.estacao());
                comando.setString(2, leitura.timestamp());
                comando.setDouble(3, leitura.umidade());
                comando.setDouble(4, leitura.temperatura_c());
                comando.setDouble(5, leitura.vento_km_h());
                comando.executeUpdate();
            }
            long id;
            try (var comando = conexao.createStatement(); var resultado = comando.executeQuery("SELECT last_insert_rowid()")) { id = resultado.getLong(1); }
            if (!regra.isEmpty()) {
                var detalhes = new java.util.LinkedHashMap<String, Object>(regra);
                detalhes.put("servico", "inversao-termica");
                detalhes.put("leitura_id", id);
                detalhes.put("leitura", com.google.gson.JsonParser.parseString(leitura.paraJson()));
                String json = new com.google.gson.Gson().toJson(detalhes);
                try (var comando = conexao.prepareStatement("INSERT INTO alertas_inversaotermica (leitura_id, tipo, mensagem, detalhes) VALUES (?, ?, ?, ?)")) {
                    comando.setLong(1, id);
                    comando.setString(2, (String)regra.get("tipo"));
                    comando.setString(3, (String)regra.get("mensagem"));
                    comando.setString(4, json);
                    comando.executeUpdate();
                }
                alertas.add(detalhes);
            }
            conexao.commit();
            return java.util.Map.of("mensagem", "Leitura recebida com sucesso.", "leitura_id", id, "alertas", alertas);
        }
    }

    public java.util.Map<String, Object> consultarAlertas(Consulta consulta) throws SQLException {
        String origem = " FROM (SELECT a.*, l.estacao, l.timestamp, l.umidade, l.temperatura_c, l.vento_km_h FROM alertas_inversaotermica a JOIN leituras_inversaotermica l ON l.id=a.leitura_id)";
        try (Connection conexao = conectar()) {
            conexao.setAutoCommit(false);
            long total;
            try (var comando = conexao.prepareStatement("SELECT count(*)" + origem + consulta.where())) {
                preencher(comando, consulta.valores());
                try (var rs = comando.executeQuery()) { total = rs.getLong(1); }
            }
            var alertas = new java.util.ArrayList<java.util.Map<String, Object>>();
            try (var comando = conexao.prepareStatement("SELECT *" + origem + consulta.where() + " ORDER BY id ASC LIMIT ? OFFSET ?")) {
                preencher(comando, consulta.valores());
                comando.setInt(consulta.valores().size()+1, consulta.limite());
                comando.setInt(consulta.valores().size()+2, consulta.offset());
                try (var rs = comando.executeQuery()) {
                    while (rs.next()) {
                        var alerta = new java.util.LinkedHashMap<String, Object>();
                        for (String campo : java.util.List.of("id", "leitura_id", "tipo", "mensagem", "criado_em", "status_notificacao", "tentativas", "enviado_em", "ultimo_erro"))
                            alerta.put(campo, rs.getObject(campo));
                        alerta.put("detalhes", com.google.gson.JsonParser.parseString(rs.getString("detalhes")));
                        alertas.add(alerta);
                    }
                }
            }
            conexao.commit();
            return java.util.Map.of("total", total, "limite", consulta.limite(), "offset", consulta.offset(), "alertas", alertas,
                    "webhook_configurado", !System.getenv().getOrDefault("ALERTA_WEBHOOK_URL", "").isBlank());
        }
    }

    /** Reserva atomica impede que dois ciclos enviem o mesmo evento simultaneamente. */
    public java.util.Map<String, Object> reservarNotificacao() throws SQLException {
        try (Connection c = conectar(); var comando = c.createStatement()) {
            comando.execute("BEGIN IMMEDIATE");
            var evento = new java.util.LinkedHashMap<String, Object>();
            try (var rs = comando.executeQuery("SELECT id, detalhes, tentativas FROM alertas_inversaotermica WHERE status_notificacao='pendente' AND proxima_tentativa <= unixepoch() ORDER BY id LIMIT 1")) {
                if (rs.next()) {
                    evento.put("id", rs.getLong("id"));
                    evento.put("detalhes", rs.getString("detalhes"));
                    evento.put("tentativas", rs.getInt("tentativas") + 1);
                }
            }
            if (!evento.isEmpty()) {
                try (var update = c.prepareStatement("UPDATE alertas_inversaotermica SET tentativas=tentativas+1, proxima_tentativa=unixepoch()+60 WHERE id=?")) {
                    update.setObject(1, evento.get("id")); update.executeUpdate();
                }
            }
            comando.execute("COMMIT");
            return evento;
        }
    }

    public void registrarEnvio(long id, boolean sucesso, int tentativas, String erro) throws SQLException {
        try (Connection c = conectar(); var comando = c.prepareStatement(
                "UPDATE alertas_inversaotermica SET status_notificacao=?, enviado_em=CASE WHEN ? THEN strftime('%Y-%m-%dT%H:%M:%fZ','now') ELSE NULL END, ultimo_erro=?, proxima_tentativa=unixepoch()+? WHERE id=?")) {
            comando.setString(1, sucesso ? "enviado" : "pendente");
            comando.setBoolean(2, sucesso);
            comando.setString(3, erro);
            comando.setInt(4, (int)Math.min(300, 5L << Math.min(tentativas - 1, 6)));
            comando.setLong(5, id);
            comando.executeUpdate();
        }
    }
}
