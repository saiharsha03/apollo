"""The phone's client certificate for mTLS (docs/PLAN.md, Security kept: "mTLS gateway"), as Zeus's Lemnos had it.

At pairing the phone sends a certificate request for its Keystore key (P-256, made on the phone, never leaves it); this
signs it with the device CA and hands back the chain. Caddy (deploy/apollo.caddy) then asks every phone route for a
certificate from that CA, so a stolen bearer token alone opens nothing. The CA is made by deploy/mtls.sh (root):
/etc/apollo/device-ca.key (root:apollo 0640) and device-ca.pem. Without it pairing still works, with no certificate.
"""
import datetime as dt

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

from . import config

DAYS = 825


class BadRequest(ValueError):
    pass


def ready() -> bool:
    return config.DEVICE_CA_KEY.exists() and config.DEVICE_CA.exists()


def check(csr_pem: str) -> x509.CertificateSigningRequest:
    """A P-256 request whose signature checks out, or BadRequest."""
    try:
        csr = x509.load_pem_x509_csr(csr_pem.encode())
    except ValueError:
        raise BadRequest("not a certificate request")
    if not csr.is_signature_valid:
        raise BadRequest("the request's signature doesn't check out")
    key = csr.public_key()
    if not isinstance(key, ec.EllipticCurvePublicKey) or key.curve.name != "secp256r1":
        raise BadRequest("expected a P-256 key")
    return csr


def sign(csr: x509.CertificateSigningRequest, now: dt.datetime | None = None) -> str:
    """The phone's certificate and the CA, PEM, leaf first. Client auth only; CN is always apollo-phone."""
    ca_key = serialization.load_pem_private_key(config.DEVICE_CA_KEY.read_bytes(), password=None)
    ca = x509.load_pem_x509_certificate(config.DEVICE_CA.read_bytes())
    now = now or dt.datetime.now(dt.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "apollo-phone")]))
        .issuer_name(ca.subject)
        .public_key(csr.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - dt.timedelta(minutes=5))
        .not_valid_after(now + dt.timedelta(days=DAYS))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(x509.KeyUsage(digital_signature=True, content_commitment=False, key_encipherment=False, data_encipherment=False,
                                     key_agreement=False, key_cert_sign=False, crl_sign=False, encipher_only=False, decipher_only=False), critical=True)
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.CLIENT_AUTH]), critical=False)
        .sign(ca_key, hashes.SHA256())
    )
    return (cert.public_bytes(serialization.Encoding.PEM) + ca.public_bytes(serialization.Encoding.PEM)).decode()
