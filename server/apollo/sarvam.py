"""Sarvam voice for the phone (as Zeus had it): text to speech for Athena's words on an Ossa call, speech to text for
Calliope voice notes and the Ask sheet. Only api.sarvam.ai is called, over https. The key (config.SARVAM_KEY) is read at
call time and never reaches a log, an error or a reply. When Sarvam can't answer the phone uses Android's own voice.
"""
import base64
import io
import json
import secrets
import urllib.request
import wave

from fastapi import APIRouter, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import Response

from . import config, tomtom

HOST = "https://api.sarvam.ai"
TTS_MODEL = "bulbul:v3"
STT_MODEL = "saarika:v2.5"
LANG = "en-IN"
TTS_MAX = 1500                # characters per speech request
PIECE_S = 25                  # Sarvam's REST speech to text takes at most 30 s per request
AUDIO_MAX = 40 * 1024 * 1024  # a 20 minute voice note at 16 kHz mono is ~38 MB
# bulbul:v3's voices (Zeus's voice.py, checked 2026-10-23); anything else falls back to ritu.
SPEAKERS = ("shubh", "aditya", "ritu", "priya", "neha", "rahul", "pooja", "rohan", "simran", "kavya", "amit", "dev", "ishita",
            "shreya", "ratan", "varun", "manan", "sumit", "roopa", "kabir", "aayan", "ashutosh", "advait", "anand", "tanya",
            "tarun", "sunny", "mani", "gokul", "vijay", "shruti", "suhani", "mohit", "kavitha", "rehan", "soham", "rupali")

Refused = tomtom.Refused


def key():
    """The key from its env-style file (SARVAM_API_KEY=..., Zeus's format) or a bare key; parsed, never sourced."""
    try:
        text = config.SARVAM_KEY.read_text().strip()
    except OSError:
        text = ""
    for line in text.splitlines():
        k, _, v = line.strip().removeprefix("export ").partition("=")
        if k.strip() in ("SARVAM_API_KEY", "SARVAM_KEY", "API_KEY") and v.strip():
            return v.strip().strip('"').strip("'")
    if text and "=" not in text:
        return text
    raise Refused("no Sarvam key on the server")


def post(path, body, ctype, k):
    req = urllib.request.Request(HOST + path, data=body, method="POST", headers={"api-subscription-key": k, "Content-Type": ctype})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read())
    except Exception as e:
        raise Refused(f"Sarvam failed ({type(e).__name__}: {tomtom.redact(str(e), k)[:200]})") from None


def tts(store, text, speaker=""):
    """WAV bytes of Athena's words in her Sarvam voice."""
    text = str(text).strip()[:TTS_MAX]
    if not text:
        raise ValueError("empty")
    speaker = speaker or store.setting("sarvam_speaker")
    k = key()
    tomtom.count(store, "sarvam", "Sarvam")
    j = post("/text-to-speech", json.dumps({"text": text, "target_language_code": LANG, "model": TTS_MODEL,
                                            "speaker": speaker if speaker in SPEAKERS else "ritu"}).encode(), "application/json", k)
    audios = j.get("audios") or []
    if not audios:
        raise Refused("Sarvam sent no audio")
    return base64.b64decode(audios[0])


def pieces(audio):
    """A WAV split into PIECE_S pieces (each its own WAV); any other audio as is (it must be under 30 s)."""
    try:
        w = wave.open(io.BytesIO(audio))
    except (wave.Error, EOFError):
        return [audio]
    step = w.getframerate() * PIECE_S
    out = []
    while True:
        frames = w.readframes(step)
        if not frames:
            return out or [audio]
        b = io.BytesIO()
        with wave.open(b, "wb") as p:
            p.setnchannels(w.getnchannels())
            p.setsampwidth(w.getsampwidth())
            p.setframerate(w.getframerate())
            p.writeframes(frames)
        out.append(b.getvalue())


def stt(store, audio, mime="audio/wav"):
    """His words from a recording (one request per 25 s piece, joined)."""
    if not audio:
        raise ValueError("empty")
    k = key()
    words = []
    for piece in pieces(audio):
        tomtom.count(store, "sarvam", "Sarvam")
        body, ctype = form({"model": STT_MODEL, "language_code": LANG}, piece, "audio.wav" if piece[:4] == b"RIFF" else "audio", mime)
        t = str(post("/speech-to-text", body, ctype, k).get("transcript") or "").strip()
        if t:
            words.append(t)
    return " ".join(words)


def form(fields, data, name, mime):
    """multipart/form-data with the fields and one file."""
    b = "apollo" + secrets.token_hex(8)
    out = b"".join(f'--{b}\r\nContent-Disposition: form-data; name="{n}"\r\n\r\n{v}\r\n'.encode() for n, v in fields.items())
    out += f'--{b}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: {mime}\r\n\r\n'.encode()
    return out + data + f"\r\n--{b}--\r\n".encode(), f"multipart/form-data; boundary={b}"


def _http(e):
    msg = str(e)
    return HTTPException(429 if "limit" in msg else 503 if "no Sarvam key" in msg else 502, msg)


def router(phone, store):
    r = APIRouter()

    @r.post("/v1/voice/tts")
    async def speak(req: Request):
        phone(req)
        b = await req.json()
        try:
            return Response(await run_in_threadpool(tts, store, b.get("text", ""), str(b.get("speaker") or "")), media_type="audio/wav")
        except ValueError:
            raise HTTPException(400, "empty")
        except Refused as e:
            raise _http(e)

    @r.post("/v1/voice/stt")
    async def listen(req: Request):
        phone(req)
        audio = await req.body()
        if len(audio) > AUDIO_MAX:
            raise HTTPException(413, "too long")
        try:
            return {"text": await run_in_threadpool(stt, store, audio, req.headers.get("content-type", "audio/wav").split(";")[0].strip())}
        except ValueError:
            raise HTTPException(400, "empty")
        except Refused as e:
            raise _http(e)

    return r
