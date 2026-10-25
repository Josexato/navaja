#!/usr/bin/env python3
"""
Verificador independiente de firmas SSJF v1 (Simple Signature JSON Format).

No depende de Navaja: sólo de Python 3.8+ y, para comprobar la firma ECDSA,
del paquete `cryptography` (pip install cryptography). Sin él, igual
comprueba los resúmenes SHA-256.

Uso:
  python3 verificar_ssjf.py firmado.pdf                 # verifica todas las firmas del PDF
  python3 verificar_ssjf.py firmado.pdf --extraer DIR   # guarda el original y la versión firmada
  python3 verificar_ssjf.py --qr "https://...#SSJF1;h=...;s=..." --clave clave.json
  python3 verificar_ssjf.py --historial historial.jsonl # verifica la cadena del historial
  python3 verificar_ssjf.py original.pdf --qr "..."     # ¿este archivo es el que dice el QR?
  python3 verificar_ssjf.py firmado.pdf --clave vieja.json --cambio cambio.json   # confiar en la clave nueva

Código de salida: 0 = todo verificado; 2 = válido pero con advertencias o sin
poder comprobarlo todo (falta `cryptography`, cambios después de la firma,
firma antigua sin sello firmado, clave sin confirmar); 1 = inválido.

Especificación: SSJF_v1.md (misma carpeta).
"""
import argparse
import base64
import hashlib
import json
import os
import re
import sys

MARKER = b"%SSJF-anexo-v1"
OK, ERR, WARN = 0, 1, 2


def worst(a, b):
    """Combina resultados: un error manda sobre una advertencia, y esta sobre el éxito."""
    return ERR if ERR in (a, b) else WARN if WARN in (a, b) else OK
QR_RE = re.compile(r"SSJF1;[^\s]+")


def b64url_dec(s):
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def b64url_enc(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def sha256(b):
    return hashlib.sha256(b).hexdigest()


def key_id(pk_compressed):
    return b64url_enc(hashlib.sha256(pk_compressed).digest()[:12])


def parse_qr(text):
    m = QR_RE.search(text)
    if not m:
        raise ValueError("no es un QR SSJF")
    core = m.group(0)
    signed, sep, sig = core.partition(";s=")
    if not sep:
        raise ValueError("falta la firma s=")
    fields = {}
    for part in signed.split(";")[1:]:
        k, _, v = part.partition("=")
        fields[k] = v
    for k in ("h", "n", "t", "k"):
        if k not in fields:
            raise ValueError("falta el campo " + k)
    if not re.fullmatch(r"[0-9a-f]{64}", fields["h"]):
        raise ValueError("h no es un SHA-256 hex en minúsculas")
    raw = b64url_dec(sig)
    if len(raw) != 64:
        raise ValueError("la firma debe tener 64 bytes")
    return {"core": core, "signed": signed.encode("utf-8"), "sig": raw, **fields}


def ecdsa_ok(pk_compressed, msg, raw):
    """True/False, o None si falta la librería cryptography."""
    try:
        from cryptography.exceptions import InvalidSignature
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature
    except ImportError:
        return None
    try:
        pub = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), pk_compressed)
        pub.verify(encode_dss_signature(int.from_bytes(raw[:32], "big"), int.from_bytes(raw[32:], "big")),
                   msg, ec.ECDSA(hashes.SHA256()))
        return True
    except (InvalidSignature, ValueError):
        return False


def check_key_change(path, keys):
    """Cambio de clave: la vieja autoriza a la nueva y la nueva prueba posesión. Si la vieja es de confianza, la nueva también."""
    k = json.load(open(path, encoding="utf-8"))
    old, new = b64url_dec(k["old_pk"]), b64url_dec(k["new_pk"])
    pop = f"SSJF1-POP;newpk={k['new_pk']};t={k['pop_t']}".encode()
    rot = f"SSJF1-ROT;oldpk={k['old_pk']};newpk={k['new_pk']};t={k['ct']};pop={k['pop_t']};r={k['reason']}".encode()
    a = ecdsa_ok(old, rot, b64url_dec(k["sig_old"]))
    b = ecdsa_ok(new, pop, b64url_dec(k["sig_new"]))
    if a is None:
        print("? cambio de clave sin comprobar (pip install cryptography)")
        return WARN
    print(f"Cambio de clave {key_id(old)} → {key_id(new)} ({k['reason']}, t={k['ct']}): "
          f"{ok(a)} autorizado por la anterior · {ok(b)} posesión de la nueva")
    if not (a and b):
        return 1
    if key_id(old) in keys:
        keys[key_id(new)] = new
        print(f"  → la clave {key_id(new)} queda como de confianza")
    else:
        print(f"  ? la clave anterior {key_id(old)} no está en tus claves de confianza (--clave)")
    return 0


def verify_sig(qr, pk_compressed):
    """True/False, o None si falta la librería cryptography."""
    try:
        from cryptography.exceptions import InvalidSignature
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature
    except ImportError:
        return None
    if key_id(pk_compressed) != qr["k"]:
        return False
    pub = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), pk_compressed)
    r = int.from_bytes(qr["sig"][:32], "big")
    s = int.from_bytes(qr["sig"][32:], "big")
    try:
        pub.verify(encode_dss_signature(r, s), qr["signed"], ec.ECDSA(hashes.SHA256()))
        return True
    except InvalidSignature:
        return False


def find_annexes(data):
    out = []
    pos = data.find(MARKER)
    while pos >= 0:
        try:
            m = re.compile(rb"\s*\d+\s+\d+\s+obj\s*<<(.*?)>>\s*stream\r?\n", re.S).match(data, pos + len(MARKER))
            length = int(re.search(rb"/Length\s+(\d+)", m.group(1)).group(1))
            body = data[m.end():m.end() + length]
            annex = json.loads(body.decode("utf-8"))
            eof = data.find(b"%%EOF", pos)
            end = len(data) if eof < 0 else eof + 5
            while end < len(data) and data[end:end + 1] in (b"\r", b"\n"):
                end += 1
            out.append((annex, end))
        except Exception as e:  # noqa: BLE001
            out.append(({"error": str(e)}, pos))
        pos = data.find(MARKER, pos + 1)
    return out


def load_key(path):
    with open(path, encoding="utf-8") as f:
        k = json.load(f)
    return b64url_dec(k["pk"])


def ok(b):
    return "✓" if b else "✗"


def check_pdf(path, keys, extract_dir):
    data = open(path, "rb").read()
    print(f"Archivo: {path}\n  {len(data)} bytes · SHA-256 {sha256(data)}")
    annexes = find_annexes(data)
    if not annexes:
        print("  Sin firmas SSJF.")
        return ERR
    rc = OK
    for i, (a, end) in enumerate(annexes, 1):
        print(f"\nFirma {i} de {len(annexes)}")
        if "error" in a:
            print("  ✗ anexo ilegible:", a["error"])
            rc = worst(rc, ERR)
            continue
        try:
            qr = parse_qr(a["qr"])
        except ValueError as e:
            print("  ✗ QR del anexo inválido:", e)
            rc = worst(rc, ERR)
            continue
        n0, n1 = a["n0"], a["n1"]
        pre = sha256(data[:n0]) == a["h0"] == qr["h"] and str(n0) == qr["n"]
        stamp = sha256(data[:n1]) == a["h1"]
        pk = keys.get(qr["k"]) or b64url_dec(a["pk"])
        known = qr["k"] in keys
        sig = verify_sig(qr, pk) if key_id(pk) == qr["k"] else False
        res = OK
        print(f"  {ok(pre)} documento original = primeros {n0} bytes, SHA-256 {a['h0']}")
        print(f"  {ok(stamp)} versión sellada = primeros {n1} bytes, SHA-256 {a['h1']}")
        if not (pre and stamp):
            res = ERR
        if sig is None:
            print("  ? firma ECDSA NO comprobada (pip install cryptography)")
            res = worst(res, WARN)
        else:
            print(f"  {ok(sig)} firma ECDSA P-256 del QR, clave {qr['k']}" + (" (clave conocida)" if known else " (clave del anexo, sin confirmar dueño)"))
            if not sig:
                res = ERR
            elif not known:
                res = worst(res, WARN)
        # Firma del sello (revisión 2): el firmante avala la versión sellada D1, no sólo el original.
        if "seal_sig" in a:
            msg = f"SSJF1-SEAL;h0={a['h0']};h1={a['h1']};n1={n1};k={qr['k']}".encode()
            seal = ecdsa_ok(pk, msg, b64url_dec(a["seal_sig"])) if key_id(pk) == qr["k"] else False
            if seal is None:
                print("  ? firma del sello NO comprobada (pip install cryptography)")
                res = worst(res, WARN)
            else:
                print(f"  {ok(seal)} firma del sello: el firmante avala la versión sellada (firma visible incluida)")
                if not seal:
                    res = ERR
        elif qr.get("r") is not None and int(qr["r"]) >= 2:
            print("  ✗ falta la firma del sello que el QR exige: el anexo fue reemplazado")
            res = ERR
        else:
            print("  ⚠ formato antiguo sin firma del sello: sólo el ORIGINAL está garantizado; lo agregado al sellar no")
            res = worst(res, WARN)
        print(f"  · hora declarada {qr['t']} (unix)" + (f" · no antes del bloque Bitcoin {qr['b']}" if "b" in qr else ""))
        tail = len(data) - end
        nxt = annexes[i][0] if i < len(annexes) else None
        if tail > 0 and not (nxt and nxt.get("n0") == end):
            print(f"  ⚠ hay {tail} bytes agregados DESPUÉS de esta firma que no están firmados")
            res = worst(res, WARN)
        if "rfc3161" in a:
            tsr = os.path.join(extract_dir or ".", f"firma{i}.tsr")
            if extract_dir:
                os.makedirs(extract_dir, exist_ok=True)
                open(tsr, "wb").write(base64.b64decode(a["rfc3161"]["tsr"]))
            print(f"  · sello RFC 3161 de {a['rfc3161']['url']}: openssl ts -verify -digest {a['h1']} -in {tsr} -CAfile <CA de la TSA>")
        if "opentimestamps" in a:
            ots = os.path.join(extract_dir or ".", f"firma{i}.ots")
            if extract_dir:
                os.makedirs(extract_dir, exist_ok=True)
                open(ots, "wb").write(base64.b64decode(a["opentimestamps"]["ots"]))
            print(f"  · OpenTimestamps: ots upgrade {ots} && ots verify -d {a['h1']} {ots}")
        if extract_dir:
            os.makedirs(extract_dir, exist_ok=True)
            open(os.path.join(extract_dir, f"firma{i}_original.pdf"), "wb").write(data[:n0])
            open(os.path.join(extract_dir, f"firma{i}_version_firmada.pdf"), "wb").write(data[:n1])
            print(f"  → extraídos en {extract_dir}/")
        print("  RESULTADO:", {OK: "✓ verificada", WARN: "⚠ válida con advertencias", ERR: "✗ INVÁLIDA"}[res])
        rc = worst(rc, res)
    return rc


def check_qr(text, keys, file_path):
    qr = parse_qr(text)
    print("QR SSJF:", qr["core"][:60] + "…")
    print(f"  documento: {qr['n']} bytes, SHA-256 {qr['h']}")
    pk = keys.get(qr["k"])
    if pk is None:
        print(f"  ? clave {qr['k']} desconocida: pasa --clave con la clave pública del firmante")
        res = WARN
    else:
        sig = verify_sig(qr, pk)
        if sig is None:
            print("  ? firma NO comprobada (pip install cryptography)")
            res = WARN
        else:
            print(f"  {ok(sig)} firma ECDSA de la clave {qr['k']}")
            res = OK if sig else ERR
    if file_path:
        data = open(file_path, "rb").read()
        same = sha256(data) == qr["h"] and len(data) == int(qr["n"])
        print(f"  {ok(same)} {file_path} " + ("ES el documento que se firmó" if same else "NO es el documento que se firmó"))
        if not same:
            res = ERR
    return res


def check_ledger(path):
    prev = "0" * 64
    n = 0
    with open(path, encoding="utf-8") as f:
        for i, line in enumerate(f, 1):
            if not line.strip():
                continue
            e = json.loads(line, object_pairs_hook=dict)
            if e.get("seq") != i or e.get("prev") != prev:
                print(f"✗ entrada {i}: la cadena se rompe (¿borrada o modificada?)")
                return 1
            priv, salt = e.pop("priv", None), e.pop("salt", None)
            if priv is not None:
                c = hashlib.sha256(b64url_dec(salt) + json.dumps(priv, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
                if c != e.get("commit"):
                    print(f"✗ entrada {i}: los datos privados no coinciden con su compromiso")
                    return 1
            prev = sha256(json.dumps(e, separators=(",", ":"), ensure_ascii=False).encode())
            n += 1
    print(f"✓ historial íntegro: {n} entradas, cabeza {prev}")
    return 0


def main():
    ap = argparse.ArgumentParser(description="Verificador de firmas SSJF v1")
    ap.add_argument("pdf", nargs="?", help="PDF firmado (o el original, junto con --qr)")
    ap.add_argument("--qr", help="texto del QR")
    ap.add_argument("--clave", action="append", default=[], help="clave pública del firmante (JSON exportado por Navaja)")
    ap.add_argument("--extraer", metavar="DIR", help="guardar original, versión firmada y sellos en DIR")
    ap.add_argument("--historial", help="verificar la cadena de un historial .jsonl")
    ap.add_argument("--cambio", action="append", default=[], help="declaración de cambio de clave (JSON), en orden")
    a = ap.parse_args()
    keys = {}
    for k in a.clave:
        pk = load_key(k)
        keys[key_id(pk)] = pk
    rc = 0
    for c in a.cambio:
        rc = worst(rc, check_key_change(c, keys))
    if a.historial:
        rc = worst(rc, check_ledger(a.historial))
    if a.qr:
        rc = worst(rc, check_qr(a.qr, keys, a.pdf))
    elif a.pdf:
        rc = worst(rc, check_pdf(a.pdf, keys, a.extraer))
    if not (a.pdf or a.qr or a.historial or a.cambio):
        ap.print_help()
        return 2
    return rc


if __name__ == "__main__":
    sys.exit(main())
