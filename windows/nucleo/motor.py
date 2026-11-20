"""Motor de bloqueo: decide si hay que bloquear y lo aplica."""
from __future__ import annotations

import os
from datetime import datetime

from . import hosts, procesos
from .config import Almacen, Estado, Limite
from .uso import AVISO_ANTES_S, NAVEGADORES, AlmacenUso, limites_en_uso, titulo_coincide

MAX_PASO_S = 10        # un hueco más largo entre dos pasos (PC suspendida) no cuenta como uso
GUARDAR_USO_S = 30     # el uso se guarda en disco cada tanto (y siempre que se agota un límite)
ESPERA_PESTANA_S = 2   # tras cerrar una pestaña, el navegador tarda un momento en cambiar el título


class Motor:
    def __init__(self, almacen: Almacen, ruta_hosts: str | None = None, uso: AlmacenUso | None = None,
                 ventana_activa=procesos.ventana_activa, cerrar_pestana=procesos.cerrar_pestana):
        self.almacen = almacen
        self.ruta_hosts = ruta_hosts or hosts.RUTA_HOSTS
        self.uso = uso or AlmacenUso(os.path.join(os.path.dirname(os.path.abspath(almacen.ruta)), "uso.json"))
        self._ventana_activa = ventana_activa
        self._cerrar_pestana = cerrar_pestana
        self.error: str | None = None
        self._pid = os.getpid()
        self._ultimo_paso: datetime | None = None
        self._uso_guardado: datetime | None = None
        self._pestana_cerrada: tuple[int, datetime] | None = None
        # Lo que pasó en el último paso, para los avisos de la interfaz.
        self.agotados: list[Limite] = []        # límites sin tiempo hoy
        self.por_agotarse: list[Limite] = []    # les acaban de quedar menos de 5 minutos
        self.pestanas_cerradas: list[str] = []  # sitios a los que se les cerró la pestaña

    def paso(self, ahora: datetime | None = None) -> tuple[Estado, list[str]]:
        """Revisa el estado y lo aplica. Devuelve el estado y las apps que cerró."""
        ahora = ahora or datetime.now()
        cfg = self.almacen.cfg
        errores = []

        if cfg.sesion_fin:
            fin = cfg.fin_sesion()
            if fin is None or fin <= ahora:       # la sesión ya terminó
                cfg.sesion_fin = None
                try:
                    self.almacen.guardar()
                except OSError as e:
                    errores.append(f"No se pudo guardar la configuración ({e}).")

        estado = cfg.estado(ahora)
        ventana = self._leer_ventana() if cfg.limites else None
        try:
            self._contar_uso(ventana, ahora)
        except OSError as e:
            errores.append(f"No se pudo guardar el tiempo de uso ({e}).")
        self.agotados = [l for l in cfg.limites if self.uso.datos.agotado(l)]

        sitios = set(cfg.sitios) if estado.activo else set()
        sitios |= {l.nombre for l in self.agotados if l.tipo == "sitio"}
        # Se reescribe en cada paso: si alguien borra las líneas a mano, vuelven.
        try:
            hosts.aplicar(sorted(sitios), self.ruta_hosts)
        except PermissionError:
            errores.append("Sin permiso para modificar el archivo hosts: "
                           "abre el Bloqueador como administrador.")
        except OSError as e:
            errores.append(f"No se pudo modificar el archivo hosts ({e}).")

        cerradas = []
        bloqueadas = set(cfg.apps) if estado.activo else set()
        bloqueadas |= {l.nombre for l in self.agotados if l.tipo == "app"}
        if bloqueadas:
            for pid, nombre in procesos.listar():
                if pid != self._pid and nombre.lower() in bloqueadas and procesos.terminar(pid):
                    cerradas.append(nombre)

        # Una página ya abierta sigue funcionando un rato aunque el sitio esté en el archivo hosts:
        # si se acabó su tiempo y está a la vista, se cierra la pestaña.
        self.pestanas_cerradas = []
        if ventana and ventana.programa in NAVEGADORES:
            sitio = next((l.nombre for l in self.agotados
                          if l.tipo == "sitio" and titulo_coincide(l.nombre, ventana.titulo)), None)
            if sitio and self._puede_cerrar_pestana(ventana.hwnd, ahora) and self._cerrar_pestana(ventana.hwnd):
                self._pestana_cerrada = (ventana.hwnd, ahora)
                self.pestanas_cerradas.append(sitio)

        self.error = " ".join(errores) or None
        return estado, cerradas

    def _leer_ventana(self) -> procesos.Ventana | None:
        try:
            return self._ventana_activa()
        except OSError:
            return None

    def _contar_uso(self, ventana: procesos.Ventana | None, ahora: datetime) -> None:
        """Suma el tiempo desde el paso anterior a las apps y sitios con límite que están a la vista."""
        uso, limites = self.uso.datos, self.almacen.cfg.limites
        self.por_agotarse = []
        anterior, self._ultimo_paso = self._ultimo_paso, ahora
        reinicio = uso.al_dia(ahora.date())
        en_uso = limites_en_uso(limites, ventana.programa, ventana.titulo) if ventana else []
        agotado_ahora = False
        if anterior is not None and en_uso:
            segundos = (ahora - anterior).total_seconds()
            # Si cambió el día en medio, el paso cuenta para el día nuevo, entero.
            if 0 < segundos <= MAX_PASO_S:
                for l in en_uso:
                    antes = uso.restantes(l)
                    uso.sumar(l, segundos)
                    despues = uso.restantes(l)
                    if antes > 0 >= despues:
                        agotado_ahora = True
                    elif antes > AVISO_ANTES_S >= despues and l.minutos * 60 > 2 * AVISO_ANTES_S:
                        self.por_agotarse.append(l)
        hace = None if self._uso_guardado is None else (ahora - self._uso_guardado).total_seconds()
        if (reinicio and limites) or agotado_ahora or (en_uso and (hace is None or not 0 <= hace < GUARDAR_USO_S)):
            self._uso_guardado = ahora
            self.uso.guardar()

    def _puede_cerrar_pestana(self, hwnd: int, ahora: datetime) -> bool:
        if self._pestana_cerrada is None:
            return True
        ventana, cuando = self._pestana_cerrada
        return ventana != hwnd or not 0 <= (ahora - cuando).total_seconds() < ESPERA_PESTANA_S

    def guardar_uso(self) -> None:
        try:
            self.uso.guardar()
        except OSError:
            pass

    def hay_agotados(self) -> bool:
        """True si hoy se acabó el tiempo de alguna app o sitio (lo bloqueado sigue hasta las 00:00)."""
        uso = self.uso.datos
        return uso.fecha == datetime.now().date().isoformat() and any(
            uso.agotado(l) for l in self.almacen.cfg.limites)

    def liberar(self) -> None:
        """Al salir: guarda el uso de hoy y quita los sitios del archivo hosts (si no hay bloqueo
        activo ni límites agotados; con eso la interfaz no deja salir)."""
        self.guardar_uso()
        if not self.almacen.cfg.estado().activo and not self.hay_agotados():
            hosts.aplicar([], self.ruta_hosts)
