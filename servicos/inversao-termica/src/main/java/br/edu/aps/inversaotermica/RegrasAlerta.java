package br.edu.aps.inversaotermica;
import java.util.Map;

/** Limites didaticos configuraveis; nao representam criterios oficiais de emergencia. */
public final class RegrasAlerta {
    private RegrasAlerta() {}
    public static Map<String, Object> avaliar(Leitura leitura) {
        double temperatura = limite("ALERTA_TEMPERATURA_C", 30, -273.15, Double.MAX_VALUE);
        double umidade = limite("ALERTA_UMIDADE_MAX", 30, 0, 100);
        double vento = limite("ALERTA_VENTO_MAX_KM_H", 5, 0, Double.MAX_VALUE);
        if (!(leitura.temperatura_c() >= temperatura && leitura.umidade() <= umidade && leitura.vento_km_h() <= vento)) return Map.of();
        return Map.of("tipo", "CALOR_AR_SECO_POUCO_VENTO", "mensagem", "Condicao de atencao: calor, ar seco e pouco vento. Nao confirma inversao termica.", "limites", Map.of("temperatura_c_min", temperatura, "umidade_max", umidade, "vento_km_h_max", vento));
    }
    private static double limite(String nome, double padrao, double minimo, double maximo) {
        String valor = System.getenv(nome);
        double numero = valor == null ? padrao : Double.parseDouble(valor);
        if (!Double.isFinite(numero) || numero < minimo || numero > maximo)
            throw new IllegalArgumentException("Limite de alerta invalido: " + nome);
        return numero;
    }
}
