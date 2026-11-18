"""Bloqueador de apps y sitios web para Windows.

Doble clic en este archivo para abrirlo. Pide permisos de administrador porque
necesita modificar el archivo hosts de Windows y cerrar programas.

Opciones:
  --minimizado   abre la ventana minimizada (se usa al iniciar Windows)
"""
import ctypes
import os
import subprocess
import sys
import traceback
from ctypes import wintypes

_ERROR_ALREADY_EXISTS = 183
_MB_ICONERROR, _MB_ICONINFO = 0x10, 0x40


def mensaje(texto: str, icono: int = _MB_ICONINFO) -> None:
    ctypes.windll.user32.MessageBoxW(None, texto, "Bloqueador", icono)


def es_admin() -> bool:
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except OSError:
        return False


def relanzar_como_admin() -> bool:
    """Vuelve a abrir el programa pidiendo permisos de administrador (ventana UAC)."""
    if getattr(sys, "frozen", False):
        exe, args = sys.executable, sys.argv[1:]
    else:
        exe, args = sys.executable, [os.path.abspath(__file__)] + sys.argv[1:]
    r = ctypes.windll.shell32.ShellExecuteW(None, "runas", exe, subprocess.list2cmdline(args), None, 1)
    return r > 32


def instancia_unica():
    """Evita abrir dos Bloqueadores a la vez. Devuelve el 'mutex' o None si ya hay uno."""
    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    k32.CreateMutexW.argtypes = [ctypes.c_void_p, wintypes.BOOL, wintypes.LPCWSTR]
    k32.CreateMutexW.restype = wintypes.HANDLE
    h = k32.CreateMutexW(None, False, "Local\\BloqueadorAppsYSitiosWeb")
    if not h or ctypes.get_last_error() == _ERROR_ALREADY_EXISTS:
        return None
    return h


def main() -> None:
    modo_prueba = os.environ.get("BLOQUEADOR_SIN_ADMIN") == "1"
    if not modo_prueba and not es_admin():
        if not relanzar_como_admin():
            mensaje("El Bloqueador necesita permisos de administrador para bloquear sitios "
                    "y cerrar aplicaciones.", _MB_ICONERROR)
        return
    mutex = instancia_unica()  # se mantiene en una variable mientras el programa corre
    if mutex is None:
        mensaje("El Bloqueador ya está abierto. Búscalo en la barra de tareas.")
        return
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)   # texto nítido en pantallas con zoom
    except (AttributeError, OSError):
        pass

    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from nucleo.interfaz import App

    App(minimizado="--minimizado" in sys.argv, modo_prueba=modo_prueba).mainloop()


if __name__ == "__main__":
    try:
        main()
    except Exception:
        detalle = traceback.format_exc()
        try:
            sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
            from nucleo.config import registrar_error
            registrar_error(detalle)
        except Exception:
            pass
        mensaje(f"Error inesperado:\n\n{detalle[-800:]}", _MB_ICONERROR)
