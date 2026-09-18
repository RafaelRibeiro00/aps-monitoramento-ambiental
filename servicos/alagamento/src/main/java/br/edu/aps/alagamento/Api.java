package br.edu.aps.alagamento;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class Api {
    public static final int PORTA = Integer.parseInt(System.getenv().getOrDefault("APS_PORT", "8082"));
    private static final Gson JSON = new Gson();
    private static final String HOST = System.getenv().getOrDefault("APS_HOST", "127.0.0.1");

    public static void main(String[] args) throws IOException {
        HttpServer servidor = criarServidor(PORTA);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> servidor.stop(0)));
        servidor.start();
        System.out.println("API alagamento: http://" + HOST + ":" + PORTA + "/leituras");
        System.out.println("Aguardando POST do Postman ou de outro cliente. Use Stop no IntelliJ para encerrar.");
    }

    // Porta zero permite que os testes usem uma porta livre.
    public static HttpServer criarServidor(int porta) throws IOException {
        return criarServidor(porta, Banco.caminhoPadrao());
    }

    public static HttpServer criarServidor(int porta, java.nio.file.Path arquivo) throws IOException {
        Banco banco = new Banco(arquivo);
        HttpServer servidor = HttpServer.create(new InetSocketAddress(HOST, porta), 0);
        servidor.createContext("/", requisicao -> receber(requisicao, banco));
        return servidor;
    }

    private static void receber(HttpExchange requisicao, Banco banco) throws IOException {
        try {
            String rota = requisicao.getRequestURI().getPath();
            if ("/health/live".equals(rota) || "/health/ready".equals(rota)) {
                if (!"GET".equals(requisicao.getRequestMethod())) {
                    requisicao.getResponseHeaders().set("Allow", "GET");
                    responder(requisicao, 405, "erro", "Use GET para consultar a saude.");
                    return;
                }
                boolean pronto = "/health/live".equals(rota) || banco.pronto();
                responder(requisicao, pronto ? 200 : 503, "status", pronto ? "ok" : "indisponivel");
                return;
            }
            if (!"/leituras".equals(requisicao.getRequestURI().getPath())) {
                responder(requisicao, 404, "erro", "Rota não encontrada; use /leituras.");
                return;
            }
            if (!"POST".equals(requisicao.getRequestMethod())) {
                requisicao.getResponseHeaders().set("Allow", "POST");
                responder(requisicao, 405, "erro", "Use POST para enviar uma leitura.");
                return;
            }
            byte[] bytes = requisicao.getRequestBody().readNBytes(4097);
            if (bytes.length > 4096) {
                responder(requisicao, 400, "erro", "O corpo deve ter no máximo 4096 bytes.");
                return;
            }

            Leitura leitura;
            try {
                leitura = Leitura.deJson(new String(bytes, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                responder(requisicao, 400, "erro", e.getMessage());
                return;
            }

            try {
                banco.salvar(leitura);
            } catch (java.sql.SQLException e) {
                System.err.println("Falha ao salvar leitura: " + e.getMessage());
                responder(requisicao, 500, "erro", "Nao foi possivel salvar a leitura. Tente novamente.");
                return;
            }
            System.out.println("Leitura salva: " + leitura.paraJson());
            responder(requisicao, 200, "mensagem", "Leitura recebida com sucesso.");
        } finally {
            requisicao.close();
        }
    }

    private static void responder(HttpExchange requisicao, int status,
                                  String campo, String mensagem) throws IOException {
        byte[] corpo = JSON.toJson(Map.of(campo, mensagem)).getBytes(StandardCharsets.UTF_8);
        requisicao.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        requisicao.sendResponseHeaders(status, corpo.length);
        requisicao.getResponseBody().write(corpo);
    }
}
