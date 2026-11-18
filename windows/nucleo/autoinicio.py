"""Inicio automático con Windows mediante el Programador de tareas.

Se usa una tarea con "privilegios más altos" para que el Bloqueador arranque
como administrador al iniciar sesión sin mostrar la ventana de permisos (UAC).
"""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
from xml.sax.saxutils import escape

TAREA = "Bloqueador de apps y sitios web"
_SIN_VENTANA = getattr(subprocess, "CREATE_NO_WINDOW", 0)
_SCRIPT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "bloqueador.pyw")


def _comando() -> tuple[str, str, str]:
    """(ejecutable, argumentos, carpeta) para lanzar el Bloqueador minimizado."""
    if getattr(sys, "frozen", False):          # versión .exe
        return sys.executable, "--minimizado", os.path.dirname(sys.executable)
    exe = sys.executable
    if exe.lower().endswith("python.exe"):     # sin ventana de consola
        sin_consola = exe[: -len("python.exe")] + "pythonw.exe"
        if os.path.exists(sin_consola):
            exe = sin_consola
    return exe, f'"{_SCRIPT}" --minimizado', os.path.dirname(_SCRIPT)


def _xml(usuario: str) -> str:
    exe, args, carpeta = (escape(x) for x in _comando())
    usuario = escape(usuario)
    return f"""<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo><Description>Abre el Bloqueador al iniciar sesión.</Description></RegistrationInfo>
  <Triggers>
    <LogonTrigger><Enabled>true</Enabled><UserId>{usuario}</UserId></LogonTrigger>
  </Triggers>
  <Principals>
    <Principal id="Autor">
      <UserId>{usuario}</UserId>
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>HighestAvailable</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Priority>5</Priority>
  </Settings>
  <Actions Context="Autor">
    <Exec><Command>{exe}</Command><Arguments>{args}</Arguments><WorkingDirectory>{carpeta}</WorkingDirectory></Exec>
  </Actions>
</Task>
"""


def _schtasks(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["schtasks", *args], capture_output=True, encoding="oem",
                          errors="replace", creationflags=_SIN_VENTANA)


def esta_activo() -> bool:
    try:
        return _schtasks("/Query", "/TN", TAREA).returncode == 0
    except OSError:
        return False


def activar() -> str | None:
    """Crea (o actualiza) la tarea. Devuelve un mensaje de error o None."""
    dominio, nombre = os.environ.get("USERDOMAIN", ""), os.environ.get("USERNAME", "")
    usuario = f"{dominio}\\{nombre}" if dominio else nombre
    fd, ruta = tempfile.mkstemp(suffix=".xml")
    os.close(fd)
    try:
        with open(ruta, "w", encoding="utf-16") as f:
            f.write(_xml(usuario))
        r = _schtasks("/Create", "/TN", TAREA, "/XML", ruta, "/F")
        return None if r.returncode == 0 else (r.stderr or r.stdout).strip()
    except OSError as e:
        return str(e)
    finally:
        try:
            os.remove(ruta)
        except OSError:
            pass


def desactivar() -> str | None:
    try:
        r = _schtasks("/Delete", "/TN", TAREA, "/F")
    except OSError as e:
        return str(e)
    return None if r.returncode == 0 else (r.stderr or r.stdout).strip()
