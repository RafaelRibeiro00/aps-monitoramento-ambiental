package br.edu.aps.alagamento;
import java.util.Map;

/** Limites didaticos configuraveis; nao representam criterios oficiais de emergencia. */
public final class RegrasAlerta {
    private RegrasAlerta() {}
    public static Map<String, Object> avaliar(Leitura leitura) {
        double nivel = limite("ALERTA_NIVEL_CORREGO_CM", 200, 0, Double.MAX_VALUE);
        double chuva = limite("ALERTA_CHUVA_MM", 50, 0, Double.MAX_VALUE);
        if (!((leitura.nivelCorregoCm() != null && leitura.nivelCorregoCm() >= nivel) || (leitura.chuva_mm() != null && leitura.chuva_mm() >= chuva))) return Map.of();
        return Map.of("tipo", "NIVEL_OU_CHUVA_ELEVADOS", "mensagem", "Nivel do corrego ou chuva acima ou igual ao limite de demonstracao.", "limites", Map.of("nivel_corrego_cm_min", nivel, "chuva_mm_min", chuva));
    }
    private static double limite(String nome, double padrao, double minimo, double maximo) {
        String valor = Configuracao.valor(nome,null);
        double numero = valor == null ? padrao : Double.parseDouble(valor);
        if (!Double.isFinite(numero) || numero < minimo || numero > maximo)
            throw new IllegalArgumentException("Limite de alerta invalido: " + nome);
        return numero;
    }
}
