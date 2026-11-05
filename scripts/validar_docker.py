"""Valida as tres imagens em containers temporarios, sem modificar o banco principal."""
from validar_local import main

if __name__ == "__main__":
    main(docker=True)
