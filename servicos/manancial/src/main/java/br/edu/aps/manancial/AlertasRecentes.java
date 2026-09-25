package br.edu.aps.manancial;
import com.google.gson.JsonObject;
import java.time.*;
import java.util.*;
final class AlertasRecentes {
    private final Clock relogio;
    private final LinkedHashMap<String, JsonObject> alertas = new LinkedHashMap<>();
    AlertasRecentes() { this(Clock.systemUTC()); }
    AlertasRecentes(Clock relogio) { this.relogio = relogio; }
    synchronized void adicionar(JsonObject alerta) {
        limpar();
        if (Instant.parse(alerta.get("criado_em").getAsString()).plusSeconds(60).isAfter(relogio.instant()))
            alertas.put(alerta.get("evento_id").getAsString(), alerta.deepCopy());
        while (alertas.size() > 10000) alertas.remove(alertas.keySet().iterator().next());
    }
    synchronized List<JsonObject> listar() {
        limpar();
        return alertas.values().stream().map(JsonObject::deepCopy).toList();
    }
    private void limpar() {
        alertas.values().removeIf(a -> !Instant.parse(a.get("criado_em").getAsString()).plusSeconds(60).isAfter(relogio.instant()));
    }
}
