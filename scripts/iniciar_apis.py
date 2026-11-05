"""Compila e inicia os tres projetos Java localmente, sem Docker."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys

RAIZ = Path(__file__).resolve().parent.parent

def ferramentas():
    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        candidatos = sorted((Path.home()/".jdks").glob("*/bin/javac.exe"))
        if candidatos:
            java_home = str(candidatos[-1].parent.parent)
    ambiente = dict(os.environ)
    if java_home:
        ambiente["JAVA_HOME"] = java_home
        ambiente["PATH"] = str(Path(java_home)/"bin") + os.pathsep + ambiente.get("PATH", "")
    java = shutil.which("java", path=ambiente.get("PATH"))
    maven = shutil.which("mvn")
    if not maven and os.name == "nt":
        candidatos = sorted(Path("C:/Program Files/JetBrains").glob("IntelliJ IDEA */plugins/maven-plugin/lib/maven3/bin/mvn.cmd"))
        if candidatos:
            maven = str(candidatos[-1])
    if not java or not maven:
        raise RuntimeError("Configure JAVA_HOME com JDK 17 e Maven no PATH.")
    return java, maven, ambiente

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--servico", choices=["todos", "manancial", "alagamento", "inversao-termica"], default="todos")
    parser.add_argument("--somente-compilar", action="store_true")
    args = parser.parse_args()
    java, maven, ambiente = ferramentas()
    subprocess.run([maven, "-B", "-ntp", "-DskipTests", "package", "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies", "-DincludeScope=runtime"], cwd=RAIZ, env=ambiente, check=True)
    if args.somente_compilar:
        return
    processos=[]
    try:
        for servico in ["manancial", "alagamento", "inversao-termica"]:
            if args.servico not in ("todos", servico):
                continue
            target=RAIZ/"servicos"/servico/"target"
            classpath=str(target/"classes")+os.pathsep+str(target/"dependency"/"*")
            pacote=servico.replace("-", "")
            processos.append(subprocess.Popen([java, "-Dfile.encoding=UTF-8", "-cp", classpath, "br.edu.aps."+pacote+".Api"], cwd=RAIZ, env=ambiente))
        print("APIs iniciadas. Ctrl+C encerra; filas pendentes permanecem em disco.", flush=True)
        while True:
            for processo in processos:
                codigo=processo.poll()
                if codigo is not None:
                    raise RuntimeError(f"Uma API encerrou com codigo {codigo}; confira a mensagem acima.")
            import time
            time.sleep(0.5)
    except KeyboardInterrupt:
        print("Encerrando APIs...")
    finally:
        for processo in processos:
            if processo.poll() is None:
                processo.terminate()
        for processo in processos:
            processo.wait(timeout=15)

if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as erro:
        print(f"Nao foi possivel iniciar: {erro}", file=sys.stderr)
        raise SystemExit(1)
