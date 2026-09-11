package br.edu.aps.alagamento;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.google.gson.annotations.SerializedName;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;

// Um record é uma classe simples para transportar dados.
// Double (em vez de double) permite distinguir null de zero.
public record Leitura(
        String sensor,
        String timestamp,
        @SerializedName("nivel_corrego_cm") Double nivelCorregoCm,
        Double chuva_mm,
        Double velocidade_agua_m_s
) {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final Set<String> CAMPOS = Set.of("sensor", "timestamp", "nivel_corrego_cm", "chuva_mm", "velocidade_agua_m_s");

    public static Leitura deJson(String corpo) {
        JsonElement elemento;
        try {
            elemento = JSON.fromJson(corpo, JsonElement.class);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("JSON inválido: envie apenas um objeto com os cinco campos.");
        }
        if (elemento == null || !elemento.isJsonObject()) {
            throw new IllegalArgumentException("JSON inválido: envie um objeto com os cinco campos.");
        }
        JsonObject objeto = elemento.getAsJsonObject();
        for (String campo : objeto.keySet()) {
            if (!CAMPOS.contains(campo)) {
                throw new IllegalArgumentException("Campo desconhecido: " + campo);
            }
        }

        String identificacao = textoObrigatorio(objeto, "sensor");
        String momento = textoObrigatorio(objeto, "timestamp");
        JsonElement medida = objeto.get("nivel_corrego_cm");
        if (medida == null || medida.isJsonNull()) {
            throw new IllegalArgumentException("O campo nivel_corrego_cm é obrigatório e não pode ser null.");
        }
        if (!medida.isJsonPrimitive() || !medida.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("O campo nivel_corrego_cm deve ser um número.");
        }
        Leitura leitura = new Leitura(identificacao, momento, medida.getAsDouble(), numeroObrigatorio(objeto, "chuva_mm"), numeroObrigatorio(objeto, "velocidade_agua_m_s"));
        leitura.validar();
        return leitura;
    }

    private static String textoObrigatorio(JsonObject objeto, String campo) {
        JsonElement valor = objeto.get(campo);
        if (valor == null || valor.isJsonNull() || !valor.isJsonPrimitive()
                || !valor.getAsJsonPrimitive().isString() || valor.getAsString().isBlank()) {
            throw new IllegalArgumentException("O campo " + campo + " deve ser um texto não vazio.");
        }
        return valor.getAsString();
    }

    private static Double numeroObrigatorio(JsonObject objeto, String campo) {
        JsonElement valor = objeto.get(campo);
        if (valor == null || valor.isJsonNull()) {
            throw new IllegalArgumentException("O campo " + campo + " é obrigatório e não pode ser null.");
        }
        if (!valor.isJsonPrimitive() || !valor.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("O campo " + campo + " deve ser um número.");
        }
        return valor.getAsDouble();
    }

    private static void validarMedida(Double valor, String campo, double minimo) {
        if (valor == null || !Double.isFinite(valor) || valor < minimo) {
            throw new IllegalArgumentException("O campo " + campo
                    + " deve ser um número finito maior ou igual a " + minimo + ".");
        }
    }

    public void validar() {
        if (sensor == null || sensor.isBlank()) {
            throw new IllegalArgumentException("O campo sensor deve ser um texto não vazio.");
        }
        try {
            // Exige segundos e fuso, conforme o formato RFC 3339 usado nesta etapa.
            if (timestamp == null || !timestamp.matches(
                    "\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?([Zz]|[+-]\\d{2}:\\d{2})")) {
                throw new DateTimeParseException("Formato inválido", "", 0);
            }
            OffsetDateTime.parse(timestamp);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "timestamp deve ser uma data/hora RFC 3339, por exemplo 2026-09-04T15:30:00Z.");
        }
        if (nivelCorregoCm == null) {
            throw new IllegalArgumentException("O campo nivel_corrego_cm é obrigatório e não pode ser null.");
        }
        if (!Double.isFinite(nivelCorregoCm) || nivelCorregoCm < 0) {
            throw new IllegalArgumentException(
                    "nivel_corrego_cm deve ser um número finito maior ou igual a zero.");
        }
        validarMedida(chuva_mm, "chuva_mm", 0);
        validarMedida(velocidade_agua_m_s, "velocidade_agua_m_s", 0);
    }

    public String paraJson() {
        return JSON.toJson(this);
    }
}
