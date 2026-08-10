"""
加密工具SDK
AES-256-GCM加密、OTK管理、安全执行
"""

import secrets
import base64
import gc
from typing import Tuple, Optional, Dict, Any


class OTKManager:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._keys: Dict[str, bytes] = {}
            cls._instance._ivs: Dict[str, bytes] = {}
        return cls._instance

    def generate(self) -> Tuple[bytes, bytes, str]:
        key = secrets.token_bytes(32)
        iv = secrets.token_bytes(12)
        key_id = secrets.token_hex(16)
        self._keys[key_id] = key
        self._ivs[key_id] = iv
        return key, iv, key_id

    def get(self, key_id: str) -> Optional[Tuple[bytes, bytes]]:
        key = self._keys.pop(key_id, None)
        iv = self._ivs.pop(key_id, None)
        if key and iv:
            return key, iv
        return None

    def cleanup(self, key_id: str):
        self._keys.pop(key_id, None)
        self._ivs.pop(key_id, None)


otk_manager = OTKManager()


def generate_keypair() -> Tuple[str, str]:
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.backends import default_backend

    private_key = rsa.generate_private_key(
        public_exponent=65537,
        key_size=2048,
        backend=default_backend()
    )
    public_key = private_key.public_key()

    private_pem = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption()
    )
    public_pem = public_key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    )

    return public_pem.decode('utf-8'), private_pem.decode('utf-8')


def encrypt_message(
    plaintext: bytes,
    public_key_pem: str
) -> Tuple[bytes, bytes, bytes, str]:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives import serialization, hashes
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.backends import default_backend

    aes_key, aes_iv, key_id = otk_manager.generate()

    aesgcm = AESGCM(aes_key)
    ciphertext = aesgcm.encrypt(aes_iv, plaintext, None)

    public_key = serialization.load_pem_public_key(
        public_key_pem.encode('utf-8'),
        backend=default_backend()
    )
    encrypted_key = public_key.encrypt(
        aes_key,
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None
        )
    )

    return ciphertext, encrypted_key, aes_iv, key_id


def decrypt_message(
    ciphertext: bytes,
    encrypted_key: bytes,
    iv: bytes,
    key_id: str,
    private_key_pem: str
) -> bytes:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    key_data = otk_manager.get(key_id)
    if key_data is None:
        raise ValueError(f"Key {key_id} not found or already used")

    aes_key = key_data[0]

    try:
        aesgcm = AESGCM(aes_key)
        plaintext = aesgcm.decrypt(iv, ciphertext, None)
        return plaintext
    finally:
        secure_wipe(aes_key)
        gc.collect()


def secure_wipe(data: bytes):
    if data is not None and isinstance(data, bytes):
        length = len(data)
        secrets.token_bytes(length)


def encrypt_skill_package(
    skill_code: str,
    public_key_pem: str
) -> Dict[str, str]:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives import serialization, hashes
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.backends import default_backend

    aes_key, aes_iv, key_id = otk_manager.generate()

    aesgcm = AESGCM(aes_key)
    encrypted_code = aesgcm.encrypt(aes_iv, skill_code.encode('utf-8'), None)

    public_key = serialization.load_pem_public_key(
        public_key_pem.encode('utf-8'),
        backend=default_backend()
    )
    encrypted_key = public_key.encrypt(
        aes_key,
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None
        )
    )

    return {
        "encrypted_code": base64.b64encode(encrypted_code).decode('utf-8'),
        "encrypted_key": base64.b64encode(encrypted_key).decode('utf-8'),
        "key_id": key_id,
        "iv": base64.b64encode(aes_iv).decode('utf-8')
    }


def decrypt_skill_package(
    package: Dict[str, str],
    private_key_pem: str
) -> str:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives import serialization, hashes
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.backends import default_backend

    ciphertext = base64.b64decode(package["encrypted_code"])
    encrypted_key = base64.b64decode(package["encrypted_key"])
    iv = base64.b64decode(package["iv"])
    key_id = package["key_id"]

    private_key = serialization.load_pem_private_key(
        private_key_pem.encode('utf-8'),
        password=None,
        backend=default_backend()
    )

    aes_key = private_key.decrypt(
        encrypted_key,
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None
        )
    )

    try:
        aesgcm = AESGCM(aes_key)
        plaintext = aesgcm.decrypt(iv, ciphertext, None)
        return plaintext.decode('utf-8')
    finally:
        secure_wipe(aes_key)
        gc.collect()


class SecureExecutor:
    def __init__(self):
        self._sensitive_data: list = []

    def add_sensitive(self, data: bytes):
        self._sensitive_data.append(data)

    def secure_cleanup(self):
        for data in self._sensitive_data:
            secure_wipe(data)
        self._sensitive_data.clear()
        gc.collect()

    def __del__(self):
        self.secure_cleanup()