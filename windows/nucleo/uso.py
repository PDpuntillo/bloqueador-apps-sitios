"""Tiempo de uso de hoy de las apps y los sitios con límite diario.

Se guarda en uso.json, junto a config.json, y vuelve a cero cada día a las 00:00. Solo se
guarda el tiempo de lo que tiene límite: no se registran otras apps ni las páginas visitadas.

El tiempo cuenta mientras la app o el sitio está en primer plano. Los navegadores no dicen qué
dirección muestran, así que un sitio se reconoce por el título de la pestaña activa, que casi
siempre lleva el nombre del sitio ("Inicio • Instagram", "Video - YouTube", "Inicio / X").
"""
from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, field
from datetime import date

from .config import Limite

NAVEGADORES = frozenset({
    "chrome.exe", "msedge.exe", "firefox.exe", "brave.exe", "opera.exe", "vivaldi.exe",
    "chromium.exe", "iexplore.exe", "waterfox.exe", "librewolf.exe", "floorp.exe", "zen.exe",
    "arc.exe", "thorium.exe", "yandex.exe", "browser.exe",
})
AVISO_ANTES_S = 5 * 60   # se avisa cuando quedan 5 minutos
# Penúltima parte de dominios como bbc.co.uk o mercadolibre.com.ar: no es el nombre del sitio.
_SEGUNDO_NIVEL = {"com", "co", "org", "net", "gob", "gov", "edu", "ac", "or", "ne", "go"}
_SEPARADOR = r"[/|•·:\-–—]"


@dataclass
class Uso:
    fecha: str = ""                                            # "AAAA-MM-DD"
    segundos: dict[str, float] = field(default_factory=dict)   # clave del límite -> segundos de hoy

    def al_dia(self, hoy: date) -> bool:
        """Si cambió el día, empieza de cero. True si hubo que reiniciar."""
        if self.fecha == hoy.isoformat():
            return False
        self.fecha, self.segundos = hoy.isoformat(), {}
        return True

    def usados(self, limite: Limite) -> float:
        return self.segundos.get(limite.clave, 0.0)

    def restantes(self, limite: Limite) -> float:
        return max(0.0, limite.minutos * 60 - self.usados(limite))

    def agotado(self, limite: Limite) -> bool:
        return self.restantes(limite) <= 0

    def sumar(self, limite: Limite, segundos: float) -> None:
        self.segundos[limite.clave] = self.usados(limite) + segundos

    def a_dict(self) -> dict:
        return {"fecha": self.fecha, "segundos": {k: round(v, 1) for k, v in self.segundos.items()}}

    @classmethod
    def desde_dict(cls, d: dict) -> "Uso":
        segundos = {}
        for k, v in dict(d.get("segundos", {})).items():
            try:
                s = float(v)
            except (TypeError, ValueError):
                continue
            if s > 0:
                segundos[str(k)] = s
        return cls(str(d.get("fecha", "")), segundos)


class AlmacenUso:
    """Lee y guarda el uso de hoy en disco."""

    def __init__(self, ruta: str):
        self.ruta = ruta
        self.datos = self._cargar()

    def _cargar(self) -> Uso:
        try:
            with open(self.ruta, encoding="utf-8") as f:
                return Uso.desde_dict(json.load(f))
        except (OSError, ValueError, TypeError, AttributeError):
            return Uso()

    def guardar(self) -> None:
        tmp = self.ruta + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.datos.a_dict(), f, ensure_ascii=False, indent=2)
        os.replace(tmp, self.ruta)


def palabra_clave(dominio: str) -> str:
    """El nombre del sitio: 'instagram.com' -> 'instagram', 'mercadolibre.com.ar' -> 'mercadolibre'."""
    partes = dominio.split(".")[:-1]
    if len(partes) > 1 and partes[-1] in _SEGUNDO_NIVEL:
        partes = partes[:-1]
    return partes[-1] if partes else dominio


def titulo_coincide(dominio: str, titulo: str) -> bool:
    """True si el título de la ventana del navegador parece ser del sitio `dominio`."""
    t = titulo.lower()
    if re.search(rf"(?<![\w.-]){re.escape(dominio)}(?![\w-])", t):   # páginas sin título muestran la dirección
        return True
    nombre = palabra_clave(dominio)
    clave = re.escape(nombre)
    if len(nombre) >= 3:
        return re.search(rf"(?<!\w){clave}(?!\w)", t) is not None
    # Un nombre corto (x.com) se confunde fácil: tiene que ir suelto entre separadores, como en
    # "Inicio / X - Google Chrome". Edge agrega " y 3 páginas más" cuando hay varias pestañas.
    return re.search(rf"(^|{_SEPARADOR})\s*{clave}(?=\s*($|{_SEPARADOR})|\s+\S{{1,3}}\s+\d+\s)", t) is not None


def limites_en_uso(limites: list[Limite], programa: str, titulo: str) -> list[Limite]:
    """Los límites que corresponden a la ventana en primer plano."""
    en_uso = []
    for l in limites:
        if l.tipo == "app" and l.nombre == programa:
            en_uso.append(l)
        elif l.tipo == "sitio" and programa in NAVEGADORES and titulo_coincide(l.nombre, titulo):
            en_uso.append(l)
    return en_uso
