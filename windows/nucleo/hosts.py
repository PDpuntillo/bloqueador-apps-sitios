"""Bloqueo de sitios web con el archivo hosts de Windows.

Cada sitio bloqueado se redirige a 0.0.0.0 (una dirección que no lleva a ningún
lado), así que no carga en ningún navegador. Las líneas del bloqueador van entre
dos marcas para poder quitarlas sin tocar el resto del archivo.
"""
from __future__ import annotations

import os
import re
import stat
import subprocess
from urllib.parse import urlsplit

RUTA_SISTEMA = os.path.join(os.environ.get("SystemRoot", r"C:\Windows"),
                            "System32", "drivers", "etc", "hosts")
# BLOQUEADOR_HOSTS permite probar el programa con un archivo falso.
RUTA_HOSTS = os.environ.get("BLOQUEADOR_HOSTS") or RUTA_SISTEMA

MARCA_INICIO = "# >>> Bloqueador (no editar este bloque) >>>"
MARCA_FIN = "# <<< Bloqueador <<<"
_DESTINOS = ("0.0.0.0", "::")
_ETIQUETA = r"(?!-)[a-z0-9-]{1,63}(?<!-)"
_DOMINIO = re.compile(rf"^{_ETIQUETA}(\.{_ETIQUETA})+$")
_SIN_VENTANA = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def normalizar_dominio(texto: str) -> str | None:
    """'https://www.YouTube.com/watch?v=1' -> 'youtube.com'. None si no es válido."""
    t = texto.strip().lower()
    if not t:
        return None
    if "://" not in t:
        t = "http://" + t
    try:
        host = (urlsplit(t).hostname or "").strip(".")
        host = host.encode("idna").decode("ascii")   # dominios con ñ o tildes
    except (ValueError, UnicodeError):
        return None
    if host.startswith("www."):
        host = host[4:]
    if len(host) > 253 or not _DOMINIO.match(host) or host.rsplit(".", 1)[-1].isdigit():
        return None
    return host


def variantes(dominio: str) -> list[str]:
    """El archivo hosts no admite comodines: se agregan las variantes más comunes.

    También las del proxy de Google Traductor, que abre cualquier sitio con otro nombre
    (www.reddit.com -> www-reddit-com.translate.goog).
    """
    nombres = [dominio, f"www.{dominio}", f"m.{dominio}"]
    traductor = [n.replace("-", "--").replace(".", "-") + ".translate.goog" for n in nombres]
    return nombres + traductor


def _es_linea_nuestra(linea: str) -> bool:
    partes = linea.split()
    return len(partes) == 2 and partes[0] in _DESTINOS


def construir(texto: str, dominios: list[str]) -> str:
    """Devuelve el contenido del hosts sin nuestro bloque anterior y con el nuevo."""
    lineas, dentro = [], False
    for linea in texto.splitlines():
        limpia = linea.strip()
        if limpia == MARCA_INICIO:
            dentro = True
            continue
        if dentro:
            if limpia == MARCA_FIN:
                dentro = False
                continue
            if _es_linea_nuestra(limpia):
                continue
            dentro = False   # bloque sin cierre: no borrar líneas ajenas
        lineas.append(linea)
    while lineas and not lineas[-1].strip():
        lineas.pop()
    if dominios:
        lineas += ["", MARCA_INICIO]
        for d in dominios:
            for nombre in variantes(d):
                lineas += [f"{destino} {nombre}" for destino in _DESTINOS]
        lineas.append(MARCA_FIN)
    return "\r\n".join(lineas) + "\r\n" if lineas else ""


def leer(ruta: str = RUTA_HOSTS) -> tuple[str, str]:
    """Devuelve (texto, codificación) del archivo hosts."""
    try:
        with open(ruta, "rb") as f:
            datos = f.read()
    except FileNotFoundError:
        return "", "utf-8"
    try:
        return datos.decode("utf-8-sig"), "utf-8"
    except UnicodeDecodeError:
        return datos.decode("latin-1"), "latin-1"


def _escribir(texto: str, codificacion: str, ruta: str) -> None:
    try:
        modo = os.stat(ruta).st_mode
        if not modo & stat.S_IWRITE:          # quitar "solo lectura"
            os.chmod(ruta, modo | stat.S_IWRITE)
    except FileNotFoundError:
        pass
    with open(ruta, "wb") as f:
        f.write(texto.encode(codificacion))


def limpiar_cache_dns() -> None:
    try:
        subprocess.run(["ipconfig", "/flushdns"], capture_output=True, timeout=15,
                       creationflags=_SIN_VENTANA)
    except (OSError, subprocess.SubprocessError):
        pass


def aplicar(dominios: list[str], ruta: str = RUTA_HOSTS) -> bool:
    """Deja el archivo hosts bloqueando exactamente `dominios`. True si lo modificó."""
    texto, codificacion = leer(ruta)
    nuevo = construir(texto, dominios)
    if nuevo.rstrip().splitlines() == texto.rstrip().splitlines():
        return False
    _escribir(nuevo, codificacion, ruta)
    if os.path.normcase(os.path.abspath(ruta)) == os.path.normcase(RUTA_SISTEMA):
        limpiar_cache_dns()
    return True
