"""Configuración del bloqueador: listas, horarios, límites diarios, cuenta regresiva y sesión.

Se guarda como JSON en %PROGRAMDATA%\\Bloqueador\\config.json.
"""
from __future__ import annotations

import json
import os
import re
from dataclasses import asdict, dataclass, field
from datetime import datetime, time, timedelta

DIAS = ["Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom"]  # índice = datetime.weekday()
_HORA = re.compile(r"^([01]?\d|2[0-3]):([0-5]\d)$")
_DIA = 24 * 60
_SEMANA = 7 * _DIA   # minutos de una semana


def carpeta_datos() -> str:
    """Carpeta donde se guardan la configuración y el registro de errores."""
    base = os.environ.get("BLOQUEADOR_DATOS") or os.path.join(
        os.environ.get("PROGRAMDATA", r"C:\ProgramData"), "Bloqueador")
    os.makedirs(base, exist_ok=True)
    return base


def registrar_error(texto: str) -> None:
    """Agrega un error al archivo error.log (el programa no tiene consola)."""
    try:
        with open(os.path.join(carpeta_datos(), "error.log"), "a", encoding="utf-8") as f:
            f.write(f"--- {datetime.now():%Y-%m-%d %H:%M:%S}\n{texto}\n")
    except OSError:
        pass


def normalizar_hora(texto: str) -> str | None:
    """'9:00' -> '09:00'. Devuelve None si el texto no es una hora HH:MM válida."""
    m = _HORA.match(texto.strip())
    if not m:
        return None
    return f"{int(m.group(1)):02d}:{m.group(2)}"


def _minutos(hhmm: str) -> int:
    h, m = hhmm.split(":")
    return int(h) * 60 + int(m)


@dataclass
class Horario:
    dias: list[int]   # 0 = lunes ... 6 = domingo
    inicio: str       # "HH:MM"
    fin: str          # "HH:MM". Si fin <= inicio, termina al día siguiente.

    def fin_actual(self, ahora: datetime) -> datetime | None:
        """Si el horario está activo en `ahora`, devuelve cuándo termina; si no, None."""
        ini, fin = _minutos(self.inicio), _minutos(self.fin)
        m = ahora.hour * 60 + ahora.minute
        hoy = ahora.weekday()
        medianoche = ahora.replace(hour=0, minute=0, second=0, microsecond=0)
        if ini < fin:
            if hoy in self.dias and ini <= m < fin:
                return medianoche + timedelta(minutes=fin)
            return None
        # Cruza la medianoche (ej. 22:00 -> 07:00), o dura 24 h si inicio == fin.
        if hoy in self.dias and m >= ini:
            return medianoche + timedelta(days=1, minutes=fin)
        if (hoy - 1) % 7 in self.dias and m < fin:
            return medianoche + timedelta(minutes=fin)
        return None

    def proximo_inicio(self, ahora: datetime) -> datetime | None:
        h, m = map(int, self.inicio.split(":"))
        for d in range(8):
            dia = ahora.date() + timedelta(days=d)
            if dia.weekday() in self.dias:
                inicio = datetime.combine(dia, time(h, m))
                if inicio > ahora:
                    return inicio
        return None

    def describir(self) -> str:
        dias = sorted(set(self.dias))
        if dias == list(range(7)):
            texto_dias = "Todos los días"
        elif dias == [0, 1, 2, 3, 4]:
            texto_dias = "Lun a Vie"
        elif dias == [5, 6]:
            texto_dias = "Sáb y Dom"
        else:
            texto_dias = ", ".join(DIAS[d] for d in dias)
        if self.inicio == self.fin == "00:00":
            horas = "todo el día"
        elif self.inicio == self.fin:
            horas = f"24 h desde las {self.inicio}"
        elif _minutos(self.fin) < _minutos(self.inicio):
            horas = f"{self.inicio} → {self.fin} del día siguiente"
        else:
            horas = f"{self.inicio} → {self.fin}"
        return f"{texto_dias}  ·  {horas}"


TIPOS_LIMITE = ("app", "sitio")
MAX_MINUTOS_LIMITE = 24 * 60 - 1   # un límite de 24 h no limitaría nada


@dataclass
class Limite:
    """Tiempo de uso permitido por día de una app ("discord.exe") o un sitio ("instagram.com")."""
    tipo: str      # "app" o "sitio"
    nombre: str
    minutos: int

    @property
    def clave(self) -> str:
        """Identifica el límite en el registro de uso: "app:discord.exe", "sitio:instagram.com"."""
        return f"{self.tipo}:{self.nombre}"


TAMANOS_CUENTA = ("chico", "mediano", "grande")
POSICIONES_CUENTA = ("arriba_izq", "arriba_der", "abajo_izq", "abajo_der")


@dataclass
class AjustesCuenta:
    """La cuenta regresiva flotante que muestra cuánto le queda a lo que tiene límite y está a la vista."""
    visible: bool = True
    tamano: str = "mediano"        # uno de TAMANOS_CUENTA
    posicion: str = "abajo_der"    # una de POSICIONES_CUENTA (siempre una esquina de la pantalla)

    @classmethod
    def desde_dict(cls, d) -> "AjustesCuenta":
        if not isinstance(d, dict):
            return cls()
        tamano, posicion = d.get("tamano"), d.get("posicion")
        return cls(
            visible=bool(d.get("visible", True)),
            tamano=tamano if tamano in TAMANOS_CUENTA else cls.tamano,
            posicion=posicion if posicion in POSICIONES_CUENTA else cls.posicion,
        )


@dataclass
class Estado:
    activo: bool
    hasta: datetime | None   # cuándo termina el bloqueo actual (None si no hay o si es permanente)
    motivo: str              # "sesión", "horario" o "sesión y horario"
    permanente: bool = False  # los horarios cubren toda la semana: el bloqueo no termina nunca


@dataclass
class Config:
    sitios: list[str] = field(default_factory=list)
    apps: list[str] = field(default_factory=list)
    horarios: list[Horario] = field(default_factory=list)
    sesion_fin: str | None = None   # fecha y hora ISO en que termina la sesión
    autoinicio: bool = False
    limites: list[Limite] = field(default_factory=list)   # límites diarios, ordenados por tipo y nombre
    cuenta: AjustesCuenta = field(default_factory=AjustesCuenta)

    def limite(self, tipo: str, nombre: str) -> Limite | None:
        return next((l for l in self.limites if l.tipo == tipo and l.nombre == nombre), None)

    def fin_sesion(self) -> datetime | None:
        if not self.sesion_fin:
            return None
        try:
            return datetime.fromisoformat(self.sesion_fin)
        except ValueError:
            return None

    def estado(self, ahora: datetime | None = None) -> Estado:
        ahora = ahora or datetime.now()
        finales, motivos = [], []
        fin = self.fin_sesion()
        if fin and fin > ahora:
            finales.append(fin)
            motivos.append("sesión")
        for h in self.horarios:
            f = h.fin_actual(ahora)
            if f:
                finales.append(f)
                if "horario" not in motivos:
                    motivos.append("horario")
        if not finales:
            return Estado(False, None, "")
        if self.horarios_sin_pausa():
            # Siempre hay algún horario activo: no existe un minuto en que termine.
            return Estado(True, None, " y ".join(motivos), permanente=True)
        # Encadenar: si al terminar empieza o sigue otro horario sin pausa, el fin es el de ese.
        # Como queda al menos un minuto libre en la semana, la cadena acaba antes de 7 días;
        # el límite es solo una protección.
        hasta = max(finales)
        limite = hasta + timedelta(days=8)
        while hasta < limite:
            siguientes = [f for f in (h.fin_actual(hasta) for h in self.horarios) if f]
            if not siguientes or max(siguientes) <= hasta:
                break
            hasta = max(siguientes)
            if "horario" not in motivos:
                motivos.append("horario")
        return Estado(True, hasta, " y ".join(motivos))

    def horarios_sin_pausa(self) -> bool:
        """True si los horarios cubren todos los minutos de la semana (el bloqueo no terminaría nunca)."""
        if not self.horarios:
            return False
        cubierto = bytearray(_SEMANA)
        for h in self.horarios:
            ini, fin = _minutos(h.inicio), _minutos(h.fin)
            largo = fin - ini if ini < fin else _DIA - ini + fin   # inicio == fin: 24 h
            for d in set(h.dias):
                a = d * _DIA + ini
                b = a + largo
                if b <= _SEMANA:
                    cubierto[a:b] = b"\x01" * largo
                else:   # el domingo sigue en el lunes
                    cubierto[a:] = b"\x01" * (_SEMANA - a)
                    cubierto[:b - _SEMANA] = b"\x01" * (b - _SEMANA)
        return 0 not in cubierto

    def proximo_inicio(self, ahora: datetime) -> datetime | None:
        inicios = [i for i in (h.proximo_inicio(ahora) for h in self.horarios) if i]
        return min(inicios) if inicios else None

    def a_dict(self) -> dict:
        d = asdict(self)
        d["horarios"] = [asdict(h) for h in self.horarios]
        d["limites"] = [asdict(l) for l in self.limites]
        return d

    @classmethod
    def desde_dict(cls, d: dict) -> "Config":
        horarios = []
        for h in d.get("horarios", []):
            try:
                inicio, fin = normalizar_hora(h["inicio"]), normalizar_hora(h["fin"])
                dias = sorted({int(x) for x in h["dias"] if 0 <= int(x) <= 6})
            except (KeyError, TypeError, ValueError):
                continue
            if inicio and fin and dias:
                horarios.append(Horario(dias, inicio, fin))
        limites: dict[str, Limite] = {}
        for l in d.get("limites", []):
            try:
                tipo, nombre, minutos = str(l["tipo"]), str(l["nombre"]).strip().lower(), int(l["minutos"])
            except (KeyError, TypeError, ValueError):
                continue
            nuevo = Limite(tipo, nombre, minutos)
            if tipo in TIPOS_LIMITE and nombre and 1 <= minutos <= MAX_MINUTOS_LIMITE:
                # Si está repetido, queda el más estricto.
                anterior = limites.get(nuevo.clave)
                if anterior is None or minutos < anterior.minutos:
                    limites[nuevo.clave] = nuevo
        return cls(
            sitios=sorted({str(s) for s in d.get("sitios", [])}),
            apps=sorted({str(a).lower() for a in d.get("apps", [])}),
            horarios=horarios,
            sesion_fin=d.get("sesion_fin") or None,
            autoinicio=bool(d.get("autoinicio", False)),
            limites=sorted(limites.values(), key=lambda l: (l.tipo, l.nombre)),
            cuenta=AjustesCuenta.desde_dict(d.get("cuenta")),
        )


class Almacen:
    """Lee y guarda la configuración en disco."""

    def __init__(self, ruta: str | None = None):
        self.ruta = ruta or os.path.join(carpeta_datos(), "config.json")
        self.cfg = self._cargar()

    def _cargar(self) -> Config:
        try:
            with open(self.ruta, encoding="utf-8") as f:
                return Config.desde_dict(json.load(f))
        except FileNotFoundError:
            return Config()
        except (OSError, ValueError, TypeError, AttributeError):
            # Archivo dañado: se guarda una copia aparte y se empieza de cero.
            try:
                os.replace(self.ruta, self.ruta + ".danado")
            except OSError:
                pass
            return Config()

    def guardar(self) -> None:
        tmp = self.ruta + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.cfg.a_dict(), f, ensure_ascii=False, indent=2)
        os.replace(tmp, self.ruta)
