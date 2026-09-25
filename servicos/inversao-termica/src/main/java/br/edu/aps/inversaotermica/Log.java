package br.edu.aps.inversaotermica;
final class Log {
    static synchronized void info(String mensagem) {
        System.out.println("[" + java.time.LocalTime.now().withNano(0) + "] [inversao-termica] " + mensagem);
    }
}
