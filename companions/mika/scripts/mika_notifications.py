"""Read-only Android notification diagnostics. Never return message content."""

from datetime import datetime, timezone
import re


PACKAGE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)*\Z")


def validate(package, dependencies, listener, user):
    if not PACKAGE.fullmatch(package) or any(not PACKAGE.fullmatch(p) for p in dependencies):
        raise ValueError("Use Android package names, not shell commands.")
    if user < 0:
        raise ValueError("The Android user must be nonnegative.")
    if listener:
        parts = listener.split("/")
        if len(parts) != 2 or not PACKAGE.fullmatch(parts[0]) or not PACKAGE.fullmatch(parts[1].lstrip(".")):
            raise ValueError("Use a notification listener component in package/class form.")


def package_state(text, user, disabled):
    active = text.split("Hidden system packages:", 1)[0]
    row = re.search(r"(?m)^\s*User " + str(user) + r":\s*(.*)$", active)
    fields = dict(re.findall(r"(\w+)=(\w+)", row[1])) if row else {}
    result = {key: {"true": True, "false": False}.get(fields.get(key))
              for key in ("installed", "stopped", "suspended", "hidden")}
    result["enabled_override"] = int(fields["enabled"]) if fields.get("enabled", "").isdigit() else None
    # Android 13 and earlier print the app id as userId=, Android 14 and later as appId=.
    app_id = re.search(r"\b(?:userId|appId)=(\d+)", active)
    result["uid"] = user * 100000 + int(app_id[1]) % 100000 if app_id else None
    result["effective_enabled"] = None
    if result["installed"] is False or result["enabled_override"] in (2, 3, 4):
        result["effective_enabled"] = False
    elif result["installed"] and disabled is not None:
        result["effective_enabled"] = not disabled
    return result


def notification_settings(text, package, uid):
    result = {"found": False, "blocked": None, "channels": None, "enabled_channels": None}
    if uid is None:
        return result
    header = re.search(r"(?m)^([ \t]*)AppSettings: " + re.escape(package) + r" \(" + str(uid) + r"\)([^\n]*)$", text)
    if not header:
        return result
    lines = []
    for line in text[header.end():].splitlines():
        if line.strip() and len(line) - len(line.lstrip()) <= len(header[1]):
            break
        lines.append(line)
    levels = []
    for line in lines:
        if "NotificationChannel{" in line and "mDeleted=true" not in line:
            level = re.search(r"\bmImportance=(-?\d+)", line)
            if level:
                levels.append(int(level[1]))
    result.update(found=True, blocked=bool(re.search(r"\bimportance=(?:NONE|0)\b", header[2])),
                  channels=len(levels), enabled_channels=sum(level > 0 for level in levels))
    return result


def appop_mode(text, operation):
    for pattern in (r"(?m)^Uid mode: " + operation + r":\s*(\w+)",
                    r"(?m)^" + operation + r":\s*(\w+)", r"Default mode:\s*(\w+)"):
        match = re.search(pattern, text)
        if match:
            return match[1]
    return None


def component_name(value):
    package, name = value.strip().split("/", 1)
    return package + "/" + (package + name if name.startswith(".") else name)


def listener_state(settings, dump, component, user=0):
    expected = component_name(component)
    allowed = {component_name(x) for x in settings.strip().split(":") if "/" in x}
    live = None
    lines = dump.splitlines()
    for index, line in enumerate(lines):
        if "Live notification listeners" not in line:
            continue
        indent = len(line) - len(line.lstrip())
        values = []
        for entry in lines[index + 1:]:
            if entry.strip() and len(entry) - len(entry.lstrip()) <= indent:
                break
            values.extend(re.findall(r"ComponentInfo\{([^}]+)\}(?:\s+\(user (\d+)\))?", entry))
        profiles = {profile for name, profile in values if "/" in name and component_name(name) == expected}
        if str(user) in profiles:
            live = True
        elif "" not in profiles:
            live = False
        break
    return {"component": expected, "access_granted": expected in allowed, "bound": live}


def inspect_notifications(adb, package, dependencies=(), listener=None, user=0):
    validate(package, dependencies, listener, user)
    errors = []

    def probe(name, *args):
        try:
            return adb.run("shell", *args)
        except Exception as error:
            # ADB errors and dumps may contain private data; retain only the error type.
            errors.append({"probe": name, "error": type(error).__name__})
            return None

    disabled_text = probe("disabled_packages", "pm", "list", "packages", "-d", "--user", str(user))
    disabled = set(re.findall(r"(?m)^package:(\S+)$", disabled_text)) if disabled_text is not None else None
    findings = []

    def inspect_package(name, dependency=False):
        raw = probe("package:" + name, "dumpsys", "package", name)
        state = package_state(raw or "", user, name in disabled if disabled is not None else None)
        state["package"] = name
        prefix = "dependency" if dependency else "source"
        if state["installed"] is False:
            findings.append({"code": prefix + "_not_installed", "package": name})
        else:
            for field, value, suffix in (("effective_enabled", False, "disabled"), ("stopped", True, "stopped"),
                                         ("suspended", True, "suspended"), ("hidden", True, "hidden")):
                if state[field] is value:
                    findings.append({"code": prefix + "_" + suffix, "package": name})
        return state

    source = inspect_package(package)
    related = [inspect_package(name, True) for name in dict.fromkeys(dependencies)]
    dump = probe("notification_settings", "dumpsys", "notification")
    notification = notification_settings(dump or "", package, source["uid"])
    mode = probe("post_notification_appop", "cmd", "appops", "get", "--user", str(user), package, "POST_NOTIFICATION")
    notification["appop"] = appop_mode(mode or "", "POST_NOTIFICATION")
    if notification["blocked"] or notification["appop"] in ("ignore", "deny", "errored"):
        findings.append({"code": "notifications_blocked", "package": package})
    if notification["channels"] and notification["enabled_channels"] == 0:
        findings.append({"code": "all_channels_blocked", "package": package})
    listener_info = None
    if listener:
        setting = probe("listener_access", "settings", "--user", str(user), "get", "secure", "enabled_notification_listeners")
        listener_info = listener_state(setting, dump or "", listener, user) if setting is not None else None
        if listener_info and not listener_info["access_granted"]:
            findings.append({"code": "listener_access_missing", "component": listener_info["component"]})
        elif listener_info and listener_info["bound"] is False:
            findings.append({"code": "listener_not_bound", "component": listener_info["component"]})
    return {"captured_at": datetime.now(timezone.utc).isoformat(), "android_user": user,
            "source": source, "dependencies": related, "notifications": notification,
            "listener": listener_info, "findings": findings, "probe_errors": errors,
            "delivery_test": "not_run", "message_content_included": False}
