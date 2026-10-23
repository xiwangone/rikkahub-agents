#!/usr/bin/env python3
"""Audit CI's actual OS/JDK CA stores; remove withdrawn G3 in private copies only.

No credentials or certificates' private keys are read. TLS verification stays enabled.
This is not a substitute for revocation checking of every intermediate certificate.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import subprocess

WITHDRAWN_G3 = "86a1ecba089c4a8d3bbe2734c612ba341d813e043cf9e8a862cd5c57a36bbe6b"
PEM = r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----"


def fingerprint(cert):
    return hashlib.sha256(ssl.PEM_cert_to_DER_cert(cert)).hexdigest()


def command(args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def main():
    temp = Path(os.environ["RUNNER_TEMP"]) / "timeline-test-trust"
    temp.mkdir(mode=0o700, exist_ok=True)
    source = Path("/etc/ssl/certs/ca-certificates.crt")
    certs = re.findall(PEM, source.read_text(), re.S)
    assert certs, "OS trust bundle contains no PEM certificates"
    kept = [cert for cert in certs if fingerprint(cert) != WITHDRAWN_G3]
    private_bundle = temp / "ca-bundle.pem"
    private_bundle.write_text("\n".join(kept) + "\n")
    java_source = Path(os.environ["JAVA_HOME"]) / "lib/security/cacerts"
    listing = command(["keytool", "-list", "-rfc", "-cacerts", "-storepass", "changeit"])
    java_certs = re.findall(PEM, listing, re.S)
    assert java_certs, "JDK trust store contains no PEM certificates"
    removed_aliases = []
    for block in listing.split("Alias name: ")[1:]:
        cert = re.search(PEM, block, re.S)
        if cert and fingerprint(cert.group()) == WITHDRAWN_G3:
            removed_aliases.append(block.splitlines()[0].strip())
    private_java = temp / "cacerts"
    shutil.copyfile(java_source, private_java)
    private_java.chmod(0o600)
    for alias in removed_aliases:
        command(["keytool", "-delete", "-alias", alias, "-keystore", str(private_java), "-storepass", "changeit"])
    post_listing = command(["keytool", "-list", "-rfc", "-keystore", str(private_java), "-storepass", "changeit"])
    assert all(fingerprint(c) != WITHDRAWN_G3 for c in re.findall(PEM, post_listing, re.S))
    report = {
        "os_release": Path("/etc/os-release").read_text(),
        "packages": command(["dpkg-query", "-W", "ca-certificates", "openssl"]),
        "openssl": command(["openssl", "version"]),
        "runner_image": {key: os.environ.get(key) for key in ("ImageOS", "ImageVersion", "RUNNER_OS", "RUNNER_ARCH")},
        "os_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "os_certificate_count": len(certs),
        "os_withdrawn_g3_present": len(certs) != len(kept),
        "jdk_source_sha256": hashlib.sha256(java_source.read_bytes()).hexdigest(),
        "jdk_certificate_count": len(java_certs),
        "jdk_withdrawn_g3_present": any(fingerprint(c) == WITHDRAWN_G3 for c in java_certs),
        "private_os_certificate_count": len(kept),
        "jdk_private_removed_aliases": removed_aliases,
        "tls_verification_disabled": False,
        "system_stores_modified": False,
        "limitations": "G3 root withdrawal only; no claim of universal intermediate revocation or application trust-store coverage.",
    }
    Path("tls-trust-audit.json").write_text(json.dumps(report, indent=2) + "\n")
    with open(os.environ["GITHUB_ENV"], "a") as env:
        for key in ("SSL_CERT_FILE", "CURL_CA_BUNDLE", "REQUESTS_CA_BUNDLE"):
            env.write(f"{key}={private_bundle}\n")
        opts = os.environ.get("JAVA_TOOL_OPTIONS", "")
        env.write(f"JAVA_TOOL_OPTIONS={opts} -Djavax.net.ssl.trustStore={private_java}\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
