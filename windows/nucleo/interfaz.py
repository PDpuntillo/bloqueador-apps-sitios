"""Ventana principal del Bloqueador (hecha con tkinter, que viene incluido con Python)."""
from __future__ import annotations

import math
import os
import time
import tkinter as tk
import traceback
from dataclasses import replace
from datetime import datetime, timedelta
from tkinter import filedialog, messagebox, ttk

from . import autoinicio, hosts, procesos
from .config import (DIAS, MAX_MINUTOS_LIMITE, AjustesCuenta, Almacen, Estado, Horario, Limite, normalizar_hora,
                     registrar_error)
from .cuenta import NOMBRES_POSICION, NOMBRES_TAMANO, CuentaFlotante, lineas_cuenta
from .motor import Motor
from .uso import Uso

SITIOS_RAPIDOS = [
    ("YouTube", ["youtube.com"]),
    ("Instagram", ["instagram.com"]),
    ("TikTok", ["tiktok.com"]),
    ("Facebook", ["facebook.com"]),
    ("X / Twitter", ["x.com", "twitter.com"]),
    ("Reddit", ["reddit.com"]),
    ("Netflix", ["netflix.com"]),
    ("Twitch", ["twitch.tv"]),
]
HORAS = [f"{h:02d}:{m:02d}" for h in range(24) for m in (0, 30)]
LIBRE = ("#E7F4EA", "#1E6B34")        # (fondo, texto)
BLOQUEADO = ("#FBE9E7", "#B3261E")
AVISO = ("#FFF4E5", "#8A4B00")
GRIS = "#6B6B6B"
INTERVALO_MS = 1000
VISTA_PREVIA_S = 4   # cuánto se ve la cuenta regresiva al cambiar su tamaño o su posición
MAX_MINUTOS_SESION = 24 * 60
SIN_PAUSA = "Con este horario el bloqueo no terminaría nunca: deja al menos un rato libre en la semana."
AYUDA_SIN_FIN = ("Tus horarios cubren toda la semana sin pausa. Quita alguno en la pestaña "
                 "Horarios para que el bloqueo pueda terminar.")


def cuando(momento: datetime, ahora: datetime) -> str:
    """'hoy a las 15:30', 'mañana a las 07:00' o 'el Lun 24/11 a las 09:00'."""
    dias = (momento.date() - ahora.date()).days
    if dias == 0:
        return f"hoy a las {momento:%H:%M}"
    if dias == 1:
        return f"mañana a las {momento:%H:%M}"
    return f"el {DIAS[momento.weekday()]} {momento:%d/%m} a las {momento:%H:%M}"


def hasta_texto(estado: Estado, ahora: datetime) -> str:
    """Hasta cuándo dura el bloqueo: 'hasta hoy a las 15:30', o 'sin fin' si es permanente."""
    if estado.permanente or estado.hasta is None:
        return "sin fin"
    return f"hasta {cuando(estado.hasta, ahora)}"


def duracion(delta: timedelta) -> str:
    s = max(0, int(delta.total_seconds()))
    h, resto = divmod(s, 3600)
    m, s = divmod(resto, 60)
    return f"{h} h {m:02d} min" if h else f"{m} min {s:02d} s"


def plural(n: int, singular: str, varios: str) -> str:
    return f"{n} {singular if n == 1 else varios}"


def texto_minutos(total: int) -> str:
    """'2 horas y 30 minutos', '1 hora' o '45 minutos'."""
    h, m = divmod(total, 60)
    partes = [plural(h, "hora", "horas")] if h else []
    if m or not h:
        partes.append(plural(m, "minuto", "minutos"))
    return " y ".join(partes)


def minutos_corto(total: int) -> str:
    """'45 min', '2 h' o '1 h 30 min'."""
    h, m = divmod(total, 60)
    if not h:
        return f"{m} min"
    return f"{h} h {m} min" if m else f"{h} h"


def minutos_elegidos(horas: str, minutos: str, maximo: int = MAX_MINUTOS_SESION) -> int | None:
    """Minutos con lo escrito en las casillas de horas y minutos (vacía cuenta como 0).
    None si no vale: los minutos van de 0 a 59 y el total, de 1 minuto a `maximo`."""
    try:
        h = int(horas.strip() or 0)
        m = int(minutos.strip() or 0)
    except ValueError:
        return None
    if not (0 <= h <= 24 and 0 <= m <= 59):
        return None
    total = h * 60 + m
    return total if 1 <= total <= maximo else None


def describir_limite(limite: Limite, uso: Uso) -> str:
    """'instagram.com  ·  30 min por día  ·  hoy 12 min, quedan 18 min'."""
    texto = f"{limite.nombre}  ·  {minutos_corto(limite.minutos)} por día  ·  "
    if uso.agotado(limite):
        return texto + "se acabó por hoy"
    usados = int(uso.usados(limite) // 60)
    return texto + f"hoy {usados} min, quedan {minutos_corto(math.ceil(uso.restantes(limite) / 60))}"


def icono(raiz: tk.Misc, tam: int = 32) -> tk.PhotoImage:
    """Dibuja un ícono de 'prohibido' (círculo rojo con barra blanca)."""
    img = tk.PhotoImage(master=raiz, width=tam, height=tam)
    c, r = (tam - 1) / 2, tam / 2 - 0.5
    for y in range(tam):
        for x in range(tam):
            if (x - c) ** 2 + (y - c) ** 2 <= r * r:
                barra = abs(y - c) <= tam * 0.1 and abs(x - c) <= r * 0.6
                img.put("#FFFFFF" if barra else BLOQUEADO[1], (x, y))
    return img


class Lista(ttk.Frame):
    """Lista con barra de desplazamiento y un texto cuando está vacía."""

    def __init__(self, padre: tk.Misc, texto_vacio: str):
        super().__init__(padre)
        self.texto_vacio = texto_vacio
        self.vacia = True
        self.lb = tk.Listbox(self, height=6, selectmode="extended", activestyle="none",
                             relief="solid", borderwidth=1, highlightthickness=0,
                             font=("Segoe UI", 10), selectbackground="#CCE4F7",
                             selectforeground="#000000")
        barra = ttk.Scrollbar(self, orient="vertical", command=self.lb.yview)
        self.lb.configure(yscrollcommand=barra.set)
        self.lb.grid(row=0, column=0, sticky="nsew")
        barra.grid(row=0, column=1, sticky="ns")
        self.columnconfigure(0, weight=1)
        self.rowconfigure(0, weight=1)

    def mostrar(self, elementos: list[str], conservar: bool = False) -> None:
        """Muestra `elementos`. Con `conservar`, mantiene la selección y el desplazamiento."""
        seleccion = self.seleccion() if conservar else []
        arriba = self.lb.yview()[0]
        self.lb.delete(0, "end")
        self.vacia = not elementos
        if self.vacia:
            self.lb.insert("end", "  " + self.texto_vacio)
            fondo = self.lb.cget("background")
            self.lb.itemconfig(0, foreground=GRIS, selectforeground=GRIS, selectbackground=fondo)
        for e in elementos:
            self.lb.insert("end", "  " + e)
        if conservar:
            for i in seleccion:
                if i < len(elementos):
                    self.lb.selection_set(i)
            self.lb.yview_moveto(arriba)

    def seleccion(self) -> list[int]:
        return [] if self.vacia else [int(i) for i in self.lb.curselection()]


class App(tk.Tk):
    def __init__(self, minimizado: bool = False, modo_prueba: bool = False):
        super().__init__()
        self.modo_prueba = modo_prueba
        self.almacen = Almacen()
        self.motor = Motor(self.almacen)
        self.estado = Estado(False, None, "")
        self.toast: tk.Toplevel | None = None
        self._avisos: dict[str, datetime] = {}
        self._ultimo_error = ""
        self._textos_limites: list[str] = []
        self._previa_hasta = 0.0
        self.escala = self.winfo_fpixels("1i") / 96

        self.title("Bloqueador")
        self.geometry(f"{self.px(660)}x{self.px(660)}")
        self.minsize(self.px(600), self.px(600))
        self._icono = icono(self)
        self.iconphoto(True, self._icono)
        self._estilos()

        self.lbl_pie = ttk.Label(self, style="Ayuda.TLabel", anchor="w", padding=(16, 6, 16, 10))
        self.lbl_pie.pack(side="bottom", fill="x")
        self._crear_banner()
        self._crear_sesion()
        self.nb = ttk.Notebook(self)
        self.nb.pack(fill="both", expand=True, padx=14, pady=(10, 0))
        self._pestana_sitios()
        self._pestana_apps()
        self._pestana_horarios()
        self._pestana_limites()
        self._pestana_ajustes()
        self.cuenta = CuentaFlotante(self, self.escala)

        self._refrescar_listas()
        self._sincronizar_autoinicio()
        self.protocol("WM_DELETE_WINDOW", self._al_cerrar)
        if minimizado:
            self.iconify()
        self._ciclo()

    # ------------------------------------------------------------------ armado
    def px(self, n: float) -> int:
        return int(round(n * self.escala))

    def _estilos(self) -> None:
        e = ttk.Style(self)
        if "vista" in e.theme_names():
            e.theme_use("vista")
        e.configure(".", font=("Segoe UI", 10))
        e.configure("TNotebook.Tab", padding=(14, 4))
        e.configure("Ayuda.TLabel", foreground=GRIS)
        e.configure("Titulo.TLabel", font=("Segoe UI Semibold", 11))
        e.configure("Accion.TButton", font=("Segoe UI Semibold", 10), padding=(12, 3))

    def _crear_banner(self) -> None:
        self.banner = tk.Frame(self, padx=18, pady=12)
        self.banner.pack(fill="x")
        self.lbl_titulo = tk.Label(self.banner, font=("Segoe UI Semibold", 16), anchor="w")
        self.lbl_titulo.pack(fill="x")
        self.lbl_detalle = tk.Label(self.banner, font=("Segoe UI", 10), anchor="w", justify="left")
        self.lbl_detalle.pack(fill="x")
        self.lbl_resumen = tk.Label(self.banner, font=("Segoe UI", 9), anchor="w", justify="left")
        self.lbl_resumen.pack(fill="x", pady=(2, 0))

    def _crear_sesion(self) -> None:
        marco = ttk.LabelFrame(self, text=" Sesión de concentración ", padding=(12, 8))
        marco.pack(fill="x", padx=14, pady=(12, 0))
        ttk.Label(marco, text="Bloquear durante").pack(side="left")
        self.var_horas = tk.StringVar(value="1")
        ttk.Spinbox(marco, textvariable=self.var_horas, from_=0, to=24, width=4
                    ).pack(side="left", padx=(6, 4))
        ttk.Label(marco, text="horas").pack(side="left")
        self.var_minutos = tk.StringVar(value="0")
        ttk.Spinbox(marco, textvariable=self.var_minutos, from_=0, to=55, increment=5, width=4
                    ).pack(side="left", padx=(10, 4))
        ttk.Label(marco, text="minutos").pack(side="left")
        self.btn_sesion = ttk.Button(marco, text="Iniciar bloqueo", style="Accion.TButton",
                                     command=self._iniciar_sesion)
        self.btn_sesion.pack(side="right")

    def _fila_quitar(self, tab: ttk.Frame, comando) -> tuple[ttk.Button, ttk.Label]:
        abajo = ttk.Frame(tab)
        abajo.pack(fill="x", pady=(8, 0))
        boton = ttk.Button(abajo, text="Quitar seleccionados", command=comando)
        boton.pack(side="left")
        cuenta = ttk.Label(abajo, style="Ayuda.TLabel")
        cuenta.pack(side="right")
        return boton, cuenta

    def _pestana_sitios(self) -> None:
        tab = ttk.Frame(self.nb, padding=12)
        self.nb.add(tab, text="Sitios web")
        fila = ttk.Frame(tab)
        fila.pack(fill="x")
        self.ent_sitio = ttk.Entry(fila)
        self.ent_sitio.pack(side="left", fill="x", expand=True, ipady=2)
        self.ent_sitio.bind("<Return>", lambda _e: self._agregar_sitio())
        ttk.Button(fila, text="Agregar", command=self._agregar_sitio).pack(side="left", padx=(8, 0))
        ttk.Label(tab, text="Escribe una dirección como youtube.com (también se bloquean www. y m.).",
                  style="Ayuda.TLabel").pack(anchor="w", pady=(4, 8))

        rapidos = ttk.Frame(tab)
        rapidos.pack(fill="x", pady=(0, 8))
        ttk.Label(rapidos, text="Agregar rápido:").grid(row=0, column=0, sticky="w", padx=(0, 6))
        for i, (nombre, dominios) in enumerate(SITIOS_RAPIDOS):
            ttk.Button(rapidos, text=nombre, command=lambda d=dominios: self._agregar_sitios(d)
                       ).grid(row=i // 4, column=1 + i % 4, sticky="ew", padx=2, pady=2)
        for col in range(1, 5):
            rapidos.columnconfigure(col, weight=1)

        self.lista_sitios = Lista(tab, "Todavía no hay sitios en la lista.")
        self.lista_sitios.pack(fill="both", expand=True)
        self.btn_quitar_sitio, self.lbl_cuenta_sitios = self._fila_quitar(tab, self._quitar_sitios)

    def _pestana_apps(self) -> None:
        tab = ttk.Frame(self.nb, padding=12)
        self.nb.add(tab, text="Aplicaciones")
        fila = ttk.Frame(tab)
        fila.pack(fill="x")
        self.cmb_app = ttk.Combobox(fila, postcommand=self._cargar_procesos)
        self.cmb_app.pack(side="left", fill="x", expand=True, ipady=2)
        self.cmb_app.bind("<Return>", lambda _e: self._agregar_app())
        ttk.Button(fila, text="Examinar…", command=self._examinar_exe).pack(side="left", padx=(8, 0))
        ttk.Button(fila, text="Agregar", command=self._agregar_app).pack(side="left", padx=(8, 0))
        ttk.Label(tab, style="Ayuda.TLabel", justify="left", wraplength=self.px(580),
                  text="Despliega la lista para elegir un programa abierto, escribe su nombre "
                       "(por ejemplo discord.exe) o búscalo con Examinar…").pack(anchor="w", pady=(4, 8))
        self.lista_apps = Lista(tab, "Todavía no hay aplicaciones en la lista.")
        self.lista_apps.pack(fill="both", expand=True)
        self.btn_quitar_app, self.lbl_cuenta_apps = self._fila_quitar(tab, self._quitar_apps)

    def _pestana_horarios(self) -> None:
        tab = ttk.Frame(self.nb, padding=12)
        self.nb.add(tab, text="Horarios")
        form = ttk.Frame(tab)
        form.pack(fill="x")
        ttk.Label(form, text="Días").grid(row=0, column=0, sticky="w", pady=(0, 8), padx=(0, 10))
        dias = ttk.Frame(form)
        dias.grid(row=0, column=1, columnspan=5, sticky="w", pady=(0, 8))
        self.var_dias = [tk.BooleanVar(value=i < 5) for i in range(7)]
        for i, d in enumerate(DIAS):
            ttk.Checkbutton(dias, text=d, variable=self.var_dias[i]).pack(side="left", padx=(0, 8))
        ttk.Label(form, text="Desde").grid(row=1, column=0, sticky="w", padx=(0, 10))
        self.var_ini = tk.StringVar(value="09:00")
        ttk.Combobox(form, textvariable=self.var_ini, values=HORAS, width=7).grid(row=1, column=1, sticky="w")
        ttk.Label(form, text="hasta").grid(row=1, column=2, sticky="w", padx=10)
        self.var_fin = tk.StringVar(value="17:00")
        ttk.Combobox(form, textvariable=self.var_fin, values=HORAS, width=7).grid(row=1, column=3, sticky="w")
        ttk.Button(form, text="Agregar horario", command=self._agregar_horario
                   ).grid(row=1, column=4, sticky="w", padx=(14, 0))
        ttk.Label(tab, style="Ayuda.TLabel", justify="left", wraplength=self.px(580),
                  text="Si la hora final es menor que la inicial, termina al día siguiente "
                       "(ej. 22:00 → 07:00). Si las dos horas son iguales, dura 24 horas. "
                       "Debe quedar al menos un rato libre en la semana.").pack(anchor="w", pady=(8, 8))
        self.lista_horarios = Lista(tab, "No hay horarios programados.")
        self.lista_horarios.pack(fill="both", expand=True)
        self.btn_quitar_horario, self.lbl_cuenta_horarios = self._fila_quitar(tab, self._quitar_horarios)

    def _pestana_limites(self) -> None:
        tab = ttk.Frame(self.nb, padding=12)
        self.nb.add(tab, text="Límites diarios")
        form = ttk.Frame(tab)
        form.pack(fill="x")
        form.columnconfigure(1, weight=1)
        ttk.Label(form, text="Límite para").grid(row=0, column=0, sticky="w", padx=(0, 10))
        tipos = ttk.Frame(form)
        tipos.grid(row=0, column=1, sticky="w")
        self.var_tipo_limite = tk.StringVar(value="sitio")
        ttk.Radiobutton(tipos, text="un sitio web", value="sitio", variable=self.var_tipo_limite,
                        command=lambda: self.cmb_limite.set("")).pack(side="left", padx=(0, 12))
        ttk.Radiobutton(tipos, text="una aplicación", value="app", variable=self.var_tipo_limite,
                        command=lambda: self.cmb_limite.set("")).pack(side="left")
        self.cmb_limite = ttk.Combobox(form, postcommand=self._cargar_opciones_limite)
        self.cmb_limite.grid(row=1, column=0, columnspan=2, sticky="ew", pady=(8, 0), ipady=2)
        self.cmb_limite.bind("<Return>", lambda _e: self._agregar_limite())

        fila = ttk.Frame(form)
        fila.grid(row=2, column=0, columnspan=2, sticky="ew", pady=(8, 0))
        ttk.Label(fila, text="Máximo por día").pack(side="left")
        self.var_lim_horas = tk.StringVar(value="0")
        ttk.Spinbox(fila, textvariable=self.var_lim_horas, from_=0, to=23, width=4
                    ).pack(side="left", padx=(6, 4))
        ttk.Label(fila, text="horas").pack(side="left")
        self.var_lim_minutos = tk.StringVar(value="30")
        ttk.Spinbox(fila, textvariable=self.var_lim_minutos, from_=0, to=55, increment=5, width=4
                    ).pack(side="left", padx=(10, 4))
        ttk.Label(fila, text="minutos").pack(side="left")
        ttk.Button(fila, text="Agregar límite", command=self._agregar_limite).pack(side="right")

        ttk.Label(tab, style="Ayuda.TLabel", justify="left", wraplength=self.px(580),
                  text="Cuenta el tiempo que la app o el sitio está en primer plano. Cuando se acaba, "
                       "queda bloqueado hasta las 00:00. Los sitios se reconocen por el título de la "
                       "pestaña (por ejemplo «Instagram» o «YouTube»). Un límite agotado no se puede "
                       "quitar ni aumentar hasta el día siguiente.").pack(anchor="w", pady=(8, 8))
        self._crear_opciones_cuenta(tab)
        self.lista_limites = Lista(tab, "No hay límites diarios.")
        self.lista_limites.pack(fill="both", expand=True)
        self.btn_quitar_limite, self.lbl_cuenta_limites = self._fila_quitar(tab, self._quitar_limites)

    def _crear_opciones_cuenta(self, tab: ttk.Frame) -> None:
        """Opciones de la cuenta regresiva flotante (al pie de la pestaña Límites diarios)."""
        ajustes = self.almacen.cfg.cuenta
        marco = ttk.LabelFrame(tab, text=" Cuenta regresiva en pantalla ", padding=(12, 8))
        marco.pack(side="bottom", fill="x", pady=(10, 0))
        self.var_cuenta = tk.BooleanVar(value=ajustes.visible)
        ttk.Checkbutton(marco, variable=self.var_cuenta, command=self._cambiar_cuenta,
                        text="Mostrar en una esquina cuánto le queda a la app o al sitio que estás usando"
                        ).pack(anchor="w")
        fila = ttk.Frame(marco)
        fila.pack(fill="x", pady=(6, 0))
        ttk.Label(fila, text="Tamaño").pack(side="left")
        self.cmb_cuenta_tamano = ttk.Combobox(fila, state="readonly", width=9,
                                              values=list(NOMBRES_TAMANO.values()))
        self.cmb_cuenta_tamano.set(NOMBRES_TAMANO[ajustes.tamano])
        self.cmb_cuenta_tamano.pack(side="left", padx=(6, 14))
        ttk.Label(fila, text="Posición").pack(side="left")
        self.cmb_cuenta_posicion = ttk.Combobox(fila, state="readonly", width=21,
                                                values=list(NOMBRES_POSICION.values()))
        self.cmb_cuenta_posicion.set(NOMBRES_POSICION[ajustes.posicion])
        self.cmb_cuenta_posicion.pack(side="left", padx=(6, 0))
        for cmb in (self.cmb_cuenta_tamano, self.cmb_cuenta_posicion):
            cmb.bind("<<ComboboxSelected>>", lambda _e: self._cambiar_cuenta())
        ttk.Button(fila, text="Ver cómo queda", command=self._vista_previa_cuenta).pack(side="right")

    def _pestana_ajustes(self) -> None:
        tab = ttk.Frame(self.nb, padding=14)
        self.nb.add(tab, text="Ajustes")
        ancho = self.px(580)
        self.var_auto = tk.BooleanVar(value=self.almacen.cfg.autoinicio)
        self.chk_auto = ttk.Checkbutton(tab, text="Abrir el Bloqueador automáticamente al iniciar Windows",
                                        variable=self.var_auto, command=self._cambiar_autoinicio)
        self.chk_auto.pack(anchor="w")
        ttk.Label(tab, style="Ayuda.TLabel", justify="left", wraplength=ancho,
                  text="Recomendado: los horarios se cumplen aunque reinicies la PC "
                       "y no pide permisos cada vez.").pack(anchor="w", padx=(22, 0), pady=(2, 12))
        ttk.Separator(tab).pack(fill="x", pady=4)
        ttk.Label(tab, text="Cómo funciona", style="Titulo.TLabel").pack(anchor="w", pady=(8, 4))
        ttk.Label(tab, justify="left", wraplength=ancho, text=(
            "•  Los sitios se bloquean en todos los navegadores usando el archivo hosts de Windows.\n"
            "•  Las aplicaciones bloqueadas se cierran en cuanto se abren.\n"
            "•  Durante un bloqueo puedes agregar sitios, apps u horarios, pero no quitarlos "
            "ni cerrar el programa.\n"
            "•  Con un límite diario, la app o el sitio se bloquea al acabarse su tiempo, hasta las "
            "00:00. Mientras tanto el programa tampoco se puede cerrar.\n"
            "•  Si una página ya estaba abierta, puede tardar un poco en bloquearse: recárgala.")
        ).pack(anchor="w")
        ttk.Label(tab, style="Ayuda.TLabel", justify="left", wraplength=ancho,
                  text=f"Configuración guardada en: {self.almacen.ruta}").pack(anchor="w", pady=(12, 0))
        self.btn_salir = ttk.Button(tab, text="Salir del Bloqueador", command=self._salir)
        self.btn_salir.pack(anchor="w", pady=(16, 0))

    # ------------------------------------------------------------- refresco
    def _ciclo(self, repetir: bool = True) -> None:
        ahora = datetime.now()
        cerradas: list[str] = []
        try:
            self.estado, cerradas = self.motor.paso(ahora)
        except Exception:   # el ciclo nunca debe detenerse
            detalle = traceback.format_exc()
            if detalle != self._ultimo_error:
                self._ultimo_error = detalle
                registrar_error(detalle)
        for nombre in cerradas:
            self._avisar(nombre, ahora)
        for sitio in self.motor.pestanas_cerradas:
            limite = self.almacen.cfg.limite("sitio", sitio)
            self._pie(f"{ahora:%H:%M:%S} · Se cerró la pestaña de {sitio}: se acabó su tiempo de hoy.")
            self._toast(f"sitio:{sitio}", f"Se acabó el tiempo de {sitio}",
                        f"Usaste los {minutos_corto(limite.minutos)} de hoy. Vuelve a estar disponible mañana."
                        if limite else "Vuelve a estar disponible mañana.", ahora)
        for limite in self.motor.por_agotarse:
            self._toast(f"aviso:{limite.clave}", f"Te quedan 5 minutos de {limite.nombre}",
                        "Después se bloquea hasta mañana.", ahora, colores=AVISO)
        self._actualizar_vista(ahora)
        self._actualizar_cuenta()
        if repetir:
            self.after(INTERVALO_MS, self._ciclo)

    def _actualizar_cuenta(self) -> None:
        """Muestra la cuenta regresiva de lo que tiene límite y está a la vista, o la oculta."""
        ajustes = self.almacen.cfg.cuenta
        try:
            if time.monotonic() < self._previa_hasta:
                self.cuenta.mostrar(self._lineas_vista_previa(), ajustes, self._hwnd_propio())
            elif ajustes.visible and self.motor.en_pantalla:
                ventana = self.motor.ventana
                self.cuenta.mostrar(lineas_cuenta(self.motor.en_pantalla, self.motor.uso.datos), ajustes,
                                    ventana.hwnd if ventana else None)
            else:
                self.cuenta.ocultar()
        except (tk.TclError, OSError):   # la cuenta es solo un aviso: no debe cortar el ciclo
            detalle = traceback.format_exc()
            if detalle != self._ultimo_error:
                self._ultimo_error = detalle
                registrar_error(detalle)

    def _lineas_vista_previa(self) -> list[tuple[str, float]]:
        """Para ver cómo queda: tu primer límite con tiempo, o uno de ejemplo."""
        uso = self.motor.uso.datos
        con_tiempo = [l for l in self.almacen.cfg.limites if not uso.agotado(l)]
        return lineas_cuenta(con_tiempo[:1], uso) or [("instagram.com", 24 * 60 + 59)]

    def _hwnd_propio(self) -> int | None:
        try:
            return int(self.wm_frame(), 16)
        except (tk.TclError, ValueError):
            return None

    def _actualizar_vista(self, ahora: datetime) -> None:
        e, cfg = self.estado, self.almacen.cfg
        if e.activo:
            fondo, color = BLOQUEADO
            origen = {"sesión": "Sesión de concentración",
                      "horario": "Horario programado"}.get(e.motivo, "Sesión y horario")
            titulo = "Bloqueo activo"
            if e.permanente or e.hasta is None:
                detalle = f"{origen} · sin fin\n{AYUDA_SIN_FIN}"
                self.title("Bloqueador · bloqueado sin fin")
            else:
                restante = duracion(e.hasta - ahora)
                detalle = f"{origen} · termina {cuando(e.hasta, ahora)} · faltan {restante}"
                self.title(f"Bloqueador · bloqueado {restante}")
        else:
            fondo, color = LIBRE
            titulo = "Sin bloqueo activo"
            prox = cfg.proximo_inicio(ahora)
            detalle = (f"Próximo horario: {cuando(prox, ahora)}." if prox
                       else "Inicia una sesión de concentración o programa un horario.")
            self.title("Bloqueador" + (" (modo prueba)" if self.modo_prueba else ""))
        agotados = self.motor.agotados
        if agotados:
            detalle += (f"\nSe acabó el tiempo de hoy de {', '.join(l.nombre for l in agotados)} "
                        "(bloqueado hasta las 00:00).")
        if self.motor.error:
            detalle += f"\nAtención: {self.motor.error}"
        resumen = " · ".join([plural(len(cfg.sitios), "sitio", "sitios"),
                              plural(len(cfg.apps), "aplicación", "aplicaciones"),
                              plural(len(cfg.horarios), "horario", "horarios"),
                              plural(len(cfg.limites), "límite diario", "límites diarios")])
        self.banner.config(bg=fondo)
        self.lbl_titulo.config(text=titulo, bg=fondo, fg=color)
        self.lbl_detalle.config(text=detalle, bg=fondo, fg="#B3261E" if self.motor.error else "#2B2B2B")
        self.lbl_resumen.config(text=resumen, bg=fondo, fg=GRIS)

        bloqueo = ["disabled"] if e.activo else ["!disabled"]
        for boton in (self.btn_quitar_sitio, self.btn_quitar_app, self.btn_quitar_limite):
            boton.state(bloqueo)
        self.btn_salir.state(["disabled"] if e.activo or agotados else ["!disabled"])
        self._mostrar_limites()
        # Salida para configuraciones viejas sin pausa: ahí los horarios sí se pueden quitar.
        self.btn_quitar_horario.state(["disabled"] if e.activo and not e.permanente else ["!disabled"])
        self.chk_auto.state(["disabled"] if e.activo and self.var_auto.get() else ["!disabled"])
        self.btn_sesion.config(text="Extender bloqueo" if e.activo else "Iniciar bloqueo")

    def _refrescar_listas(self) -> None:
        cfg = self.almacen.cfg
        self.lista_sitios.mostrar(cfg.sitios)
        self.lista_apps.mostrar(cfg.apps)
        self.lista_horarios.mostrar([h.describir() for h in cfg.horarios])
        self.lbl_cuenta_sitios.config(text=plural(len(cfg.sitios), "sitio", "sitios"))
        self.lbl_cuenta_apps.config(text=plural(len(cfg.apps), "aplicación", "aplicaciones"))
        self.lbl_cuenta_horarios.config(text=plural(len(cfg.horarios), "horario", "horarios"))
        self.lbl_cuenta_limites.config(text=plural(len(cfg.limites), "límite", "límites"))
        self._mostrar_limites(forzar=True)

    def _mostrar_limites(self, forzar: bool = False) -> None:
        """Muestra los límites con el tiempo de hoy. Solo redibuja la lista si cambió algún texto."""
        textos = [describir_limite(l, self.motor.uso.datos) for l in self.almacen.cfg.limites]
        if forzar or textos != self._textos_limites:
            self._textos_limites = textos
            self.lista_limites.mostrar(textos, conservar=not forzar)

    def _pie(self, texto: str) -> None:
        self.lbl_pie.config(text=texto)

    def _guardar_y_aplicar(self) -> None:
        try:
            self.almacen.guardar()
        except OSError as e:
            messagebox.showerror("No se pudo guardar", str(e), parent=self)
        self._refrescar_listas()
        self._ciclo(repetir=False)

    def _hay_bloqueo(self, permitir_permanente: bool = False) -> bool:
        e = self.almacen.cfg.estado()
        if e.activo and not (permitir_permanente and e.permanente):
            texto = "No puedes quitar elementos mientras el bloqueo está activo. Espera a que termine."
            if e.permanente:
                texto = f"El bloqueo es sin fin. {AYUDA_SIN_FIN}"
            messagebox.showwarning("Bloqueo activo", texto, parent=self)
            return True
        return False

    def _ya_hay_bloqueo_mas_largo(self, actual: Estado, fin: datetime, ahora: datetime) -> bool:
        if actual.activo and (actual.permanente or actual.hasta is None or actual.hasta >= fin):
            texto = f"El bloqueo actual ya sigue {hasta_texto(actual, ahora)}."
            if actual.permanente:
                texto += f"\n\n{AYUDA_SIN_FIN}"
            messagebox.showinfo("Ya hay un bloqueo más largo", texto, parent=self)
            return True
        return False

    # ---------------------------------------------------------------- avisos
    def _avisar(self, nombre: str, ahora: datetime) -> None:
        """Se cerró la app `nombre`: por un bloqueo o porque se acabó su tiempo de hoy."""
        clave = nombre.lower()
        limite = next((l for l in self.motor.agotados if l.tipo == "app" and l.nombre == clave), None)
        if limite and not (self.estado.activo and clave in self.almacen.cfg.apps):
            self._pie(f"{ahora:%H:%M:%S} · Se cerró {nombre}: se acabó su tiempo de hoy.")
            self._toast(f"app:{clave}", f"Se acabó el tiempo de {nombre}",
                        f"Usaste los {minutos_corto(limite.minutos)} de hoy. Vuelve a estar disponible mañana.",
                        ahora)
            return
        self._pie(f"{ahora:%H:%M:%S} · Se cerró {nombre} porque está bloqueado.")
        texto = f"El bloqueo sigue {hasta_texto(self.estado, ahora)}." if self.estado.activo else None
        self._toast(f"app:{clave}", f"{nombre} está bloqueado", texto, ahora)

    def _toast(self, clave: str, titulo: str, texto: str | None, ahora: datetime,
               colores: tuple[str, str] = BLOQUEADO) -> None:
        """Aviso flotante abajo a la derecha. El mismo aviso no se repite antes de 20 s."""
        ultimo = self._avisos.get(clave)
        if ultimo and 0 <= (ahora - ultimo).total_seconds() < 20:
            return
        self._avisos[clave] = ahora
        self._cerrar_toast(self.toast)
        fondo, color = colores
        t = tk.Toplevel(self)
        self.toast = t
        t.overrideredirect(True)
        t.attributes("-topmost", True)
        borde = tk.Frame(t, bg=color, padx=2, pady=2)
        borde.pack()
        caja = tk.Frame(borde, bg=fondo, padx=16, pady=10)
        caja.pack()
        tk.Label(caja, text=titulo, bg=fondo, fg=color, font=("Segoe UI Semibold", 11)).pack(anchor="w")
        if texto:
            tk.Label(caja, text=texto, bg=fondo, fg="#3A3A3A", font=("Segoe UI", 10)).pack(anchor="w")
        t.update_idletasks()
        x = t.winfo_screenwidth() - t.winfo_reqwidth() - self.px(24)
        y = t.winfo_screenheight() - t.winfo_reqheight() - self.px(72)
        if self.cuenta.visible and self.almacen.cfg.cuenta.posicion == "abajo_der":
            y -= self.cuenta.alto() + self.px(8)   # encima de la cuenta regresiva, sin taparla
        t.geometry(f"+{x}+{y}")
        t.after(4500, lambda: self._cerrar_toast(t))

    def _cerrar_toast(self, t: tk.Toplevel | None) -> None:
        if t is None:
            return
        try:
            t.destroy()
        except tk.TclError:
            pass
        if self.toast is t:
            self.toast = None

    # -------------------------------------------------------------- acciones
    def _iniciar_sesion(self) -> None:
        minutos = minutos_elegidos(self.var_horas.get(), self.var_minutos.get())
        if minutos is None:
            messagebox.showwarning("Duración no válida",
                                   "Elige entre 1 minuto y 24 horas (los minutos van de 0 a 59).", parent=self)
            return
        cfg = self.almacen.cfg
        if not cfg.sitios and not cfg.apps:
            messagebox.showinfo("Nada que bloquear", "Primero agrega sitios web o aplicaciones a tus listas.",
                                parent=self)
            return
        ahora = datetime.now().replace(microsecond=0)
        fin = ahora + timedelta(minutes=minutos)
        if self._ya_hay_bloqueo_mas_largo(cfg.estado(ahora), fin, ahora):
            return
        # Fin real: si al terminar la sesión sigue un horario sin pausa, dura hasta el fin de ese.
        previsto = replace(cfg, sesion_fin=fin.isoformat(timespec="seconds")).estado(ahora)
        que = f"{plural(len(cfg.sitios), 'sitio', 'sitios')} y {plural(len(cfg.apps), 'aplicación', 'aplicaciones')}"
        texto = f"Se bloquearán {que} durante {texto_minutos(minutos)}, hasta {cuando(fin, ahora)}."
        if previsto.permanente or (previsto.hasta and previsto.hasta > fin):
            texto += (f"\nJusto después sigue un horario, así que el bloqueo durará "
                      f"{hasta_texto(previsto, ahora)}.")
        if not messagebox.askyesno(
                "Confirmar bloqueo",
                f"{texto}\n\nNo podrás detenerlo, quitar elementos de las listas ni cerrar el programa "
                "hasta entonces.\n\n¿Empezar?", icon="warning", parent=self):
            return
        # El fin se calcula al confirmar, no al abrir el diálogo: este pudo quedar abierto un rato.
        ahora = datetime.now().replace(microsecond=0)
        fin = ahora + timedelta(minutes=minutos)
        if self._ya_hay_bloqueo_mas_largo(cfg.estado(ahora), fin, ahora):
            return
        cfg.sesion_fin = fin.isoformat(timespec="seconds")
        self._guardar_y_aplicar()
        self._pie(f"Bloqueo iniciado {hasta_texto(cfg.estado(ahora), ahora)}.")

    def _agregar_sitio(self) -> None:
        texto = self.ent_sitio.get().strip()
        if not texto:
            self.ent_sitio.focus_set()
            return
        dominio = hosts.normalizar_dominio(texto)
        if not dominio:
            messagebox.showwarning("Dirección no válida",
                                   f"«{texto}» no parece una dirección web. Ejemplo: youtube.com", parent=self)
            return
        if self._agregar_sitios([dominio]):
            self.ent_sitio.delete(0, "end")

    def _agregar_sitios(self, dominios: list[str]) -> bool:
        cfg = self.almacen.cfg
        nuevos = [d for d in dominios if d not in cfg.sitios]
        if not nuevos:
            self._pie("Ese sitio ya está en la lista.")
            return False
        cfg.sitios = sorted(cfg.sitios + nuevos)
        self._guardar_y_aplicar()
        self._pie(f"Agregado: {', '.join(nuevos)}.")
        return True

    def _quitar_sitios(self) -> None:
        if self._hay_bloqueo():
            return
        cfg = self.almacen.cfg
        quitar = {cfg.sitios[i] for i in self.lista_sitios.seleccion()}
        if not quitar:
            self._pie("Selecciona uno o más sitios para quitar.")
            return
        cfg.sitios = [s for s in cfg.sitios if s not in quitar]
        self._guardar_y_aplicar()

    def _cargar_procesos(self) -> None:
        self.cmb_app["values"] = procesos.nombres_en_ejecucion()

    def _examinar_exe(self) -> None:
        ruta = filedialog.askopenfilename(parent=self, title="Elige el programa que quieres bloquear",
                                          filetypes=[("Programas", "*.exe")],
                                          initialdir=os.environ.get("ProgramFiles", "C:\\"))
        if ruta:
            self.cmb_app.set(ruta)
            self._agregar_app()

    def _agregar_app(self) -> None:
        texto = self.cmb_app.get()
        if not texto.strip():
            self.cmb_app.focus_set()
            return
        nombre, error = procesos.normalizar_app(texto)
        if error or not nombre:
            messagebox.showwarning("No se puede agregar", error or "Nombre no válido.", parent=self)
            return
        cfg = self.almacen.cfg
        if nombre in cfg.apps:
            self._pie(f"{nombre} ya está en la lista.")
            return
        cfg.apps = sorted(cfg.apps + [nombre])
        self.cmb_app.set("")
        self._guardar_y_aplicar()
        self._pie(f"Agregado: {nombre}.")

    def _quitar_apps(self) -> None:
        if self._hay_bloqueo():
            return
        cfg = self.almacen.cfg
        quitar = {cfg.apps[i] for i in self.lista_apps.seleccion()}
        if not quitar:
            self._pie("Selecciona una o más aplicaciones para quitar.")
            return
        cfg.apps = [a for a in cfg.apps if a not in quitar]
        self._guardar_y_aplicar()

    def _agregar_horario(self) -> None:
        dias = [i for i, v in enumerate(self.var_dias) if v.get()]
        if not dias:
            messagebox.showwarning("Faltan días", "Marca al menos un día.", parent=self)
            return
        ini, fin = normalizar_hora(self.var_ini.get()), normalizar_hora(self.var_fin.get())
        if not ini or not fin:
            messagebox.showwarning("Hora no válida", "Escribe las horas como HH:MM, por ejemplo 09:00.",
                                   parent=self)
            return
        nuevo = Horario(dias, ini, fin)
        cfg = self.almacen.cfg
        if nuevo in cfg.horarios:
            self._pie("Ese horario ya existe.")
            return
        con = replace(cfg, horarios=cfg.horarios + [nuevo])
        if con.horarios_sin_pausa():
            messagebox.showwarning("Horario no permitido", SIN_PAUSA, parent=self)
            return
        ahora = datetime.now()
        antes, despues = cfg.estado(ahora), con.estado(ahora)
        en_curso = nuevo.fin_actual(ahora) is not None
        # También avisa si el horario, aunque no esté en curso, alarga el bloqueo en marcha.
        alarga = (antes.activo and antes.hasta is not None and despues.hasta is not None
                  and despues.hasta > antes.hasta)
        if en_curso or alarga:
            if antes.activo:
                titulo = "El bloqueo se alargará" if alarga else "Bloqueo activo"
                texto = f"Con este horario el bloqueo seguirá {hasta_texto(despues, ahora)}"
            else:
                titulo = "El bloqueo empezará ahora"
                texto = (f"Este horario está en curso: el bloqueo se activará de inmediato "
                         f"{hasta_texto(despues, ahora)}")
            if not messagebox.askyesno(titulo, f"{texto} y no podrás quitarlo antes.\n\n¿Agregarlo?",
                                       icon="warning", parent=self):
                return
        cfg.horarios.append(nuevo)
        self._guardar_y_aplicar()
        self._pie(f"Horario agregado: {nuevo.describir()}.")

    def _quitar_horarios(self) -> None:
        if self._hay_bloqueo(permitir_permanente=True):
            return
        cfg = self.almacen.cfg
        indices = set(self.lista_horarios.seleccion())
        if not indices:
            self._pie("Selecciona uno o más horarios para quitar.")
            return
        cfg.horarios = [h for i, h in enumerate(cfg.horarios) if i not in indices]
        self._guardar_y_aplicar()

    def _cargar_opciones_limite(self) -> None:
        if self.var_tipo_limite.get() == "app":
            self.cmb_limite["values"] = procesos.nombres_en_ejecucion()
        else:
            self.cmb_limite["values"] = [d for _, dominios in SITIOS_RAPIDOS for d in dominios]

    def _no_se_puede_aflojar(self, limites: list[Limite], accion: str) -> bool:
        """Quitar o aumentar un límite solo se puede sin bloqueo activo y si hoy no se agotó."""
        if self.almacen.cfg.estado().activo:
            messagebox.showwarning("Bloqueo activo", f"No puedes {accion} límites mientras el bloqueo "
                                   "está activo. Espera a que termine.", parent=self)
            return True
        agotados = [l.nombre for l in limites if self.motor.uso.datos.agotado(l)]
        if agotados:
            messagebox.showwarning("Tiempo agotado", f"Hoy ya se acabó el tiempo de {', '.join(agotados)}. "
                                   f"Podrás {accion} su límite mañana.", parent=self)
            return True
        return False

    def _agregar_limite(self) -> None:
        tipo = self.var_tipo_limite.get()
        texto = self.cmb_limite.get().strip()
        if not texto:
            self.cmb_limite.focus_set()
            return
        if tipo == "sitio":
            nombre = hosts.normalizar_dominio(texto)
            if not nombre:
                messagebox.showwarning("Dirección no válida",
                                       f"«{texto}» no parece una dirección web. Ejemplo: instagram.com", parent=self)
                return
        else:
            nombre, error = procesos.normalizar_app(texto)
            if error or not nombre:
                messagebox.showwarning("No se puede agregar", error or "Nombre no válido.", parent=self)
                return
        minutos = minutos_elegidos(self.var_lim_horas.get(), self.var_lim_minutos.get(), MAX_MINUTOS_LIMITE)
        if minutos is None:
            messagebox.showwarning("Tiempo no válido", "Elige entre 1 minuto y 23 horas y 59 minutos por día "
                                   "(los minutos van de 0 a 59).", parent=self)
            return
        cfg = self.almacen.cfg
        nuevo = Limite(tipo, nombre, minutos)
        actual = cfg.limite(tipo, nombre)
        if actual and actual.minutos == minutos:
            self._pie("Ese límite ya existe.")
            return
        if actual and minutos > actual.minutos and self._no_se_puede_aflojar([actual], "aumentar"):
            return
        uso = self.motor.uso.datos
        if uso.agotado(nuevo) and not (actual and uso.agotado(actual)):
            usados = texto_minutos(int(uso.usados(nuevo) // 60))
            if not messagebox.askyesno("Se bloqueará ahora",
                                       f"Hoy ya usaste {nombre} {usados}: con este límite se bloqueará de "
                                       "inmediato hasta mañana y no podrás quitarlo hasta entonces.\n\n¿Agregarlo?",
                                       icon="warning", parent=self):
                return
        cfg.limites = sorted([l for l in cfg.limites if l.clave != nuevo.clave] + [nuevo],
                             key=lambda l: (l.tipo, l.nombre))
        self.cmb_limite.set("")
        self._guardar_y_aplicar()
        self._pie(f"Límite {'cambiado' if actual else 'agregado'}: {nombre}, "
                  f"{minutos_corto(minutos)} por día.")

    def _quitar_limites(self) -> None:
        cfg = self.almacen.cfg
        quitar = [cfg.limites[i] for i in self.lista_limites.seleccion() if i < len(cfg.limites)]
        if not quitar:
            self._pie("Selecciona uno o más límites para quitar.")
            return
        if self._no_se_puede_aflojar(quitar, "quitar"):
            return
        claves = {l.clave for l in quitar}
        cfg.limites = [l for l in cfg.limites if l.clave not in claves]
        self._guardar_y_aplicar()

    def _cambiar_cuenta(self) -> None:
        """Guarda las opciones de la cuenta regresiva y la muestra un momento para ver cómo queda."""
        tamanos = {v: k for k, v in NOMBRES_TAMANO.items()}
        posiciones = {v: k for k, v in NOMBRES_POSICION.items()}
        cfg = self.almacen.cfg
        cfg.cuenta = AjustesCuenta(
            visible=self.var_cuenta.get(),
            tamano=tamanos.get(self.cmb_cuenta_tamano.get(), cfg.cuenta.tamano),
            posicion=posiciones.get(self.cmb_cuenta_posicion.get(), cfg.cuenta.posicion),
        )
        try:
            self.almacen.guardar()
        except OSError as e:
            messagebox.showerror("No se pudo guardar", str(e), parent=self)
        if cfg.cuenta.visible:
            self._vista_previa_cuenta()
        else:
            self._previa_hasta = 0.0
            self._actualizar_cuenta()
            self._pie("Cuenta regresiva desactivada.")

    def _vista_previa_cuenta(self) -> None:
        self._previa_hasta = time.monotonic() + VISTA_PREVIA_S
        self._actualizar_cuenta()
        texto = "Así se ve la cuenta regresiva."
        if not self.almacen.cfg.cuenta.visible:
            texto += " Está desactivada: marca la casilla para verla mientras usas algo con límite."
        self._pie(texto)

    def _sincronizar_autoinicio(self) -> None:
        if self.modo_prueba:
            return
        activo = autoinicio.esta_activo()
        if activo:
            autoinicio.activar()   # actualiza la ruta por si moviste la carpeta
        self.var_auto.set(activo)
        if self.almacen.cfg.autoinicio != activo:
            self.almacen.cfg.autoinicio = activo
            try:
                self.almacen.guardar()
            except OSError:
                pass

    def _cambiar_autoinicio(self) -> None:
        quiere = self.var_auto.get()
        if not quiere and self.almacen.cfg.estado().activo:
            self.var_auto.set(True)
            self._pie("No puedes desactivar el inicio automático durante un bloqueo.")
            return
        if self.modo_prueba:
            self._pie("Modo prueba: no se modifica el inicio de Windows.")
        else:
            error = autoinicio.activar() if quiere else autoinicio.desactivar()
            if error:
                self.var_auto.set(not quiere)
                messagebox.showerror("No se pudo cambiar el inicio automático", error, parent=self)
                return
        self.almacen.cfg.autoinicio = quiere
        self._guardar_y_aplicar()

    def _al_cerrar(self) -> None:
        estado = self.almacen.cfg.estado()
        if estado.activo:
            extra = f"\n{AYUDA_SIN_FIN}" if estado.permanente else ""
            messagebox.showinfo("Bloqueo activo",
                                f"El bloqueo sigue {hasta_texto(estado, datetime.now())}.{extra}\n"
                                "El Bloqueador se minimizará y seguirá funcionando.", parent=self)
            self.iconify()
            return
        if self.motor.hay_agotados():
            nombres = ", ".join(l.nombre for l in self.motor.agotados)
            messagebox.showinfo("Tiempo agotado",
                                f"Hoy se acabó el tiempo de {nombres}.\n"
                                "El Bloqueador se minimizará y seguirá funcionando hasta las 00:00.", parent=self)
            self.iconify()
            return
        self._salir()

    def _salir(self) -> None:
        cfg = self.almacen.cfg
        if cfg.estado().activo or self.motor.hay_agotados():
            return
        texto = "¿Cerrar el Bloqueador?"
        if cfg.horarios or cfg.limites:
            que = " ni ".join(x for x, hay in (("tus horarios", cfg.horarios),
                                                ("tus límites diarios", cfg.limites)) if hay)
            texto += f"\n\nMientras esté cerrado no se aplicarán {que}"
            texto += (" (volverá a abrirse cuando inicies sesión en Windows)." if self.var_auto.get() else ".")
        if not messagebox.askyesno("Salir", texto, parent=self):
            return
        try:
            self.motor.liberar()
        except OSError:
            pass
        self.destroy()

    def report_callback_exception(self, exc, val, tb) -> None:  # errores en botones
        registrar_error("".join(traceback.format_exception(exc, val, tb)))
        messagebox.showerror("Error inesperado", f"{val}\n\nLos detalles se guardaron en error.log.",
                             parent=self)
