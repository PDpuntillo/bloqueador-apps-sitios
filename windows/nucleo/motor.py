"""Motor de bloqueo: decide si hay que bloquear y lo aplica."""
from __future__ import annotations

import os
from datetime import datetime

from . import hosts, procesos
from .config import Almacen, Estado


class Motor:
    def __init__(self, almacen: Almacen, ruta_hosts: str | None = None):
        self.almacen = almacen
        self.ruta_hosts = ruta_hosts or hosts.RUTA_HOSTS
        self.error: str | None = None
        self._pid = os.getpid()

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

        # Se reescribe en cada paso: si alguien borra las líneas a mano, vuelven.
        try:
            hosts.aplicar(cfg.sitios if estado.activo else [], self.ruta_hosts)
        except PermissionError:
            errores.append("Sin permiso para modificar el archivo hosts: "
                           "abre el Bloqueador como administrador.")
        except OSError as e:
            errores.append(f"No se pudo modificar el archivo hosts ({e}).")

        cerradas = []
        if estado.activo and cfg.apps:
            bloqueadas = set(cfg.apps)
            for pid, nombre in procesos.listar():
                if pid != self._pid and nombre.lower() in bloqueadas and procesos.terminar(pid):
                    cerradas.append(nombre)

        self.error = " ".join(errores) or None
        return estado, cerradas

    def liberar(self) -> None:
        """Quita los sitios del archivo hosts (al salir, si no hay bloqueo activo)."""
        if not self.almacen.cfg.estado().activo:
            hosts.aplicar([], self.ruta_hosts)
