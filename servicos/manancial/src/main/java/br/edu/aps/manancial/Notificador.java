package br.edu.aps.manancial;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;

/** Fila persistente com entrega pelo menos uma vez; destino deduplica Idempotency-Key. */
public final class Notificador implements AutoCloseable {
    private final Banco banco;
    private final URI destino;
    private final String token;
    private final HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "notificacoes-manancial"); t.setDaemon(true); return t;
    });

    public Notificador(Banco banco) {
        this(banco, Configuracao.valor("ALERTA_WEBHOOK_URL", Configuracao.valor("MANANCIAL_WEBHOOK_URL", "")), Configuracao.valor("ALERTA_WEBHOOK_TOKEN", Configuracao.valor("MANANCIAL_WEBHOOK_TOKEN", "")));
    }
    Notificador(Banco banco, String url, String token) {
        this.banco = banco;
        this.token = token;
        this.destino = url.isBlank() ? null : URI.create(url);
        if (destino != null && (!("http".equals(destino.getScheme()) || "https".equals(destino.getScheme()))
                || destino.getHost() == null || destino.getUserInfo() != null || destino.getFragment() != null))
            throw new IllegalArgumentException("ALERTA_WEBHOOK_URL deve ser uma URL HTTP(S) sem credenciais embutidas.");
    }
    public void iniciar() {
        if (destino == null) {
            System.out.println("Alertas locais ativos; webhook externo nao configurado.");
            return;
        }
        executor.scheduleWithFixedDelay(this::processarPendentes, 0, 5, TimeUnit.SECONDS);
    }
    void processarPendentes() {
        if (destino == null) return;
        try {
            for (int i = 0; i < 10 && !Thread.currentThread().isInterrupted(); i++) {
                var evento = banco.reservarNotificacao();
                if (evento.isEmpty()) return;
                long id = (Long)evento.get("id");
                int tentativas = (Integer)evento.get("tentativas");
                boolean sucesso = false;
                String erro = null;
                try {
                    var pedido = HttpRequest.newBuilder(destino).timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "aps-manancial-" + id)
                            .POST(HttpRequest.BodyPublishers.ofString((String)evento.get("detalhes")));
                    if (!token.isBlank()) pedido.header("Authorization", "Bearer " + token);
                    int status = cliente.send(pedido.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
                    sucesso = status >= 200 && status < 300;
                    if (!sucesso) erro = "HTTP " + status;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    erro = "Envio interrompido";
                } catch (Exception e) {
                    // Nao registra URL, token ou resposta externa que possa conter credenciais.
                    erro = e.getClass().getSimpleName();
                }
                banco.registrarEnvio(id, sucesso, tentativas, erro);
                Log.info(sucesso ? "Alerta enviado ao webhook: " + id : "Alerta pendente: " + id + " | tentativa " + tentativas + " | " + erro);
            }
        } catch (java.sql.SQLException e) {
            System.err.println("Fila de alertas temporariamente indisponivel; nova tentativa no proximo ciclo.");
        }
    }
    @Override public void close() { executor.shutdownNow(); }
}
