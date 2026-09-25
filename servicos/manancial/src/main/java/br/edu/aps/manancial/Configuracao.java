package br.edu.aps.manancial;
import java.nio.file.*;
final class Configuracao {
    static String valor(String nome,String padrao) {
        String ambiente=System.getenv(nome);if(ambiente!=null)return ambiente;
        Path raiz=raiz();if(raiz!=null) {
            Path arquivo=raiz.resolve(".env");
            if(Files.isRegularFile(arquivo))try {
                for(String linha:Files.readAllLines(arquivo)) {
                    linha=linha.strip();if(linha.startsWith("#")||!linha.contains("="))continue;
                    String[] par=linha.split("=",2);
                    if(par[0].strip().equals(nome)){String v=par[1].strip();if(v.length()>1&&((v.startsWith("\"")&&v.endsWith("\""))||(v.startsWith("'")&&v.endsWith("'"))))v=v.substring(1,v.length()-1);return v;}
                }
            }catch(java.io.IOException e){throw new IllegalStateException("Nao foi possivel ler .env",e);}
        }
        return padrao;
    }
    static Path raiz(){Path p=Path.of("").toAbsolutePath();while(p!=null){if(Files.exists(p.resolve("pom.xml"))&&Files.isDirectory(p.resolve("servicos")))return p;p=p.getParent();}return null;}
}
