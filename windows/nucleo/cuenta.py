"""Cuenta regresiva flotante: cuánto le queda hoy a la app o al sitio con límite que está a la vista.

Es una ventanita siempre encima, en una esquina de la pantalla donde está la ventana activa.
No recibe el foco ni los clics: pasan a lo que está debajo.
"""
from __future__ import annotations

import ctypes
import math
import re
import tkinter as tk
import tkinter.font as tkfont
from ctypes import wintypes

from .config import AjustesCuenta, Limite
from .uso import AVISO_ANTES_S, Uso

NOMBRES_TAMANO = {"chico": "Chico", "mediano": "Mediano", "grande": "Grande"}
NOMBRES_POSICION = {
    "arriba_izq": "Arriba a la izquierda",
    "arriba_der": "Arriba a la derecha",
    "abajo_izq": "Abajo a la izquierda",
    "abajo_der": "Abajo a la derecha",
}
PUNTOS = {"chico": 10, "mediano": 14, "grande": 20}   # tamaño de la letra de cada tamaño
MARGEN = 12              # distancia al borde de la pantalla (px a 100 % de escala)
FONDO = "#202124"
FONDO_AVISO = "#B3261E"  # en los últimos 5 minutos
TEXTO = "#FFFFFF"
OPACIDAD = 0.9


def texto_restante(segundos: float) -> str:
    """'12:34', '1:02:03' o '0:05'. Redondea hacia arriba: muestra 0:01 hasta el último segundo."""
    s = max(0, math.ceil(round(segundos, 3)))
    h, resto = divmod(s, 3600)
    m, s = divmod(resto, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


def nombre_cuenta(limite: Limite) -> str:
    """'instagram.com' o 'discord' (el programa, sin el .exe)."""
    if limite.tipo == "app" and limite.nombre.endswith(".exe"):
        return limite.nombre[:-4]
    return limite.nombre


def lineas_cuenta(limites: list[Limite], uso: Uso) -> list[tuple[str, float]]:
    """(nombre, segundos que le quedan hoy) de cada límite, el más urgente primero."""
    return sorted(((nombre_cuenta(l), uso.restantes(l)) for l in limites), key=lambda x: x[1])


def texto_cuenta(lineas: list[tuple[str, float]]) -> str:
    """'instagram.com   12:34', una línea por límite."""
    return "\n".join(f"{nombre}   {texto_restante(s)}" for nombre, s in lineas)


def con_cifra(texto: str, cifra: str) -> str:
    """`texto` con todas las cifras cambiadas por `cifra` (para medir el ancho más grande)."""
    return re.sub(r"\d", cifra, texto)


def en_aviso(lineas: list[tuple[str, float]]) -> bool:
    """True si a alguno le quedan 5 minutos o menos (la cuenta se pone roja)."""
    return any(s <= AVISO_ANTES_S for _, s in lineas)


def posicion_en_area(area: tuple[int, int, int, int], ancho: int, alto: int, posicion: str,
                     margen: int) -> tuple[int, int]:
    """Esquina superior izquierda de la cuenta dentro de `area` (izq, arriba, der, abajo)."""
    izq, arriba, der, abajo = area
    x = izq + margen if posicion.endswith("_izq") else der - ancho - margen
    y = arriba + margen if posicion.startswith("arriba") else abajo - alto - margen
    return max(izq, x), max(arriba, y)


# ----------------------------------------------------------------- Windows
_u32 = ctypes.WinDLL("user32", use_last_error=True)


class _RECT(ctypes.Structure):
    _fields_ = [("left", wintypes.LONG), ("top", wintypes.LONG),
                ("right", wintypes.LONG), ("bottom", wintypes.LONG)]


class _MONITORINFO(ctypes.Structure):
    _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", _RECT), ("rcWork", _RECT),
                ("dwFlags", wintypes.DWORD)]


_u32.MonitorFromWindow.argtypes = [wintypes.HWND, wintypes.DWORD]
_u32.MonitorFromWindow.restype = wintypes.HANDLE
_u32.GetMonitorInfoW.argtypes = [wintypes.HANDLE, ctypes.POINTER(_MONITORINFO)]
_u32.GetMonitorInfoW.restype = wintypes.BOOL
_u32.GetWindowLongW.argtypes = [wintypes.HWND, ctypes.c_int]
_u32.GetWindowLongW.restype = wintypes.LONG
_u32.SetWindowLongW.argtypes = [wintypes.HWND, ctypes.c_int, wintypes.LONG]
_u32.SetWindowLongW.restype = wintypes.LONG
_u32.SetWindowPos.argtypes = [wintypes.HWND, wintypes.HWND, ctypes.c_int, ctypes.c_int,
                              ctypes.c_int, ctypes.c_int, wintypes.UINT]
_u32.SetWindowPos.restype = wintypes.BOOL
_u32.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]
_u32.ShowWindow.restype = wintypes.BOOL

_MONITOR_DEFAULTTOPRIMARY = 1
_MONITOR_DEFAULTTONEAREST = 2
_GWL_EXSTYLE = -20
_WS_EX_TOPMOST = 0x00000008
_WS_EX_TRANSPARENT = 0x00000020     # los clics pasan a la ventana de abajo
_WS_EX_TOOLWINDOW = 0x00000080      # no aparece en la barra de tareas ni en Alt+Tab
_WS_EX_LAYERED = 0x00080000
_WS_EX_NOACTIVATE = 0x08000000      # nunca recibe el foco
_HWND_TOPMOST = -1
_SWP_NOSIZE, _SWP_NOMOVE, _SWP_NOACTIVATE, _SWP_SHOWWINDOW = 0x0001, 0x0002, 0x0010, 0x0040
_SW_HIDE = 0


def area_trabajo(hwnd: int | None) -> tuple[int, int, int, int] | None:
    """Área útil (sin la barra de tareas) de la pantalla donde está `hwnd`, o de la principal."""
    flags = _MONITOR_DEFAULTTONEAREST if hwnd else _MONITOR_DEFAULTTOPRIMARY
    monitor = _u32.MonitorFromWindow(hwnd or None, flags)
    if not monitor:
        return None
    info = _MONITORINFO()
    info.cbSize = ctypes.sizeof(_MONITORINFO)
    if not _u32.GetMonitorInfoW(monitor, ctypes.byref(info)):
        return None
    r = info.rcWork
    return r.left, r.top, r.right, r.bottom


class CuentaFlotante:
    """La ventanita de la cuenta regresiva. Se crea oculta y solo cambia de texto, lugar y
    visibilidad sin activarse, para no quitarle nunca el foco a lo que estás usando."""

    def __init__(self, raiz: tk.Tk, escala: float = 1.0):
        self.escala = escala
        self.visible = False
        self._fuentes: dict[int, tuple[tkfont.Font, str]] = {}   # puntos -> (fuente, su cifra más ancha)
        self.t = tk.Toplevel(raiz)
        self.t.overrideredirect(True)
        self.t.geometry("+-32000+-32000")
        try:
            self.t.attributes("-alpha", OPACIDAD)
        except tk.TclError:
            pass
        self.lbl = tk.Label(self.t, bg=FONDO, fg=TEXTO, justify="left", anchor="w")
        self.lbl.pack(fill="both", expand=True)
        self.t.update_idletasks()
        try:
            self.hwnd = int(self.t.wm_frame(), 16)
        except (tk.TclError, ValueError):
            self.hwnd = 0
        if self.hwnd:
            estilo = _u32.GetWindowLongW(self.hwnd, _GWL_EXSTYLE)
            _u32.SetWindowLongW(self.hwnd, _GWL_EXSTYLE, estilo | _WS_EX_LAYERED | _WS_EX_TRANSPARENT
                                | _WS_EX_TOOLWINDOW | _WS_EX_NOACTIVATE | _WS_EX_TOPMOST)
            _u32.ShowWindow(self.hwnd, _SW_HIDE)
        else:
            self.t.withdraw()

    def _px(self, n: float) -> int:
        return int(round(n * self.escala))

    def mostrar(self, lineas: list[tuple[str, float]], ajustes: AjustesCuenta, cerca_de: int | None) -> None:
        """Muestra `lineas` en la esquina elegida de la pantalla donde está la ventana `cerca_de`."""
        fondo = FONDO_AVISO if en_aviso(lineas) else FONDO
        puntos = PUNTOS.get(ajustes.tamano, PUNTOS["mediano"])
        fuente, cifra = self._fuente(puntos)
        texto = texto_cuenta(lineas)
        self.t.configure(bg=fondo)
        self.lbl.configure(text=texto, bg=fondo, font=fuente,
                           padx=self._px(puntos * 0.9), pady=self._px(puntos * 0.4))
        self.t.update_idletasks()
        # Las cifras no miden todas lo mismo: el cuadro se calcula con la más ancha, para que no
        # cambie de ancho (ni se mueva su borde) cada segundo.
        lineas_texto = texto.splitlines() or [""]
        extra = (max(fuente.measure(con_cifra(l, cifra)) for l in lineas_texto)
                 - max(fuente.measure(l) for l in lineas_texto))
        ancho, alto = self.t.winfo_reqwidth() + max(0, extra), self.t.winfo_reqheight()
        area = area_trabajo(cerca_de) or (0, 0, self.t.winfo_screenwidth(), self.t.winfo_screenheight())
        x, y = posicion_en_area(area, ancho, alto, ajustes.posicion, self._px(MARGEN))
        self.t.geometry(f"{ancho}x{alto}+{x}+{y}")
        self.t.update_idletasks()
        if self.hwnd:
            # Encima de todo, sin activarla: Tk la activaría al mostrarla.
            _u32.SetWindowPos(self.hwnd, _HWND_TOPMOST, 0, 0, 0, 0,
                              _SWP_NOMOVE | _SWP_NOSIZE | _SWP_NOACTIVATE | _SWP_SHOWWINDOW)
        elif not self.visible:
            self.t.deiconify()
            self.t.attributes("-topmost", True)
        self.visible = True

    def _fuente(self, puntos: int) -> tuple[tkfont.Font, str]:
        if puntos not in self._fuentes:
            fuente = tkfont.Font(root=self.t, family="Segoe UI Semibold", size=puntos)
            self._fuentes[puntos] = (fuente, max("0123456789", key=fuente.measure))
        return self._fuentes[puntos]

    def ocultar(self) -> None:
        if not self.visible:
            return
        if self.hwnd:
            _u32.ShowWindow(self.hwnd, _SW_HIDE)
        else:
            self.t.withdraw()
        self.visible = False

    def alto(self) -> int:
        return self.t.winfo_height() if self.visible else 0
