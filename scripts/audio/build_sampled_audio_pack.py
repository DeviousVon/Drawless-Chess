#!/usr/bin/env python3
"""Generate Android-ready CC0 foley for Drawless Chess."""
from __future__ import annotations

import argparse, hashlib, json, math, random, shutil, subprocess, sys, time, urllib.request, wave
from pathlib import Path
import numpy as np

SR = 48_000
RNG = random.Random(0xD12A55)
UA = "Drawless-Chess-Audio-Pack/1.0"
SOURCES = {
    "board": {
        "title": "Board Game Pieces.WAV", "creator": "taure", "license": "CC0-1.0",
        "page": "https://freesound.org/people/taure/sounds/555190/",
        "urls": [
            "https://raw.githubusercontent.com/benyoung32/LastSpikeClient/fb0b2d640cea0f7dedb9d78024fc5666c14cfde0/public/sounds/slide.wav",
            "https://cdn.freesound.org/previews/555/555190_2940947-hq.mp3",
        ],
        "use": "Distinct wooden board-piece placements, clicks and slides.",
    },
    "capture": {
        "title": "Piece Capture.mp3", "creator": "el_boss", "license": "CC0-1.0",
        "page": "https://freesound.org/people/el_boss/sounds/546120/",
        "urls": ["https://cdn.freesound.org/previews/546/546120_9129912-hq.mp3"],
        "use": "Real chess capture layered with distinct board impacts.",
    },
    "firework": {
        "title": "2 Firework pops.wav", "creator": "Rudmer_Rotteveel", "license": "CC0-1.0",
        "page": "https://freesound.org/people/Rudmer_Rotteveel/sounds/334042/",
        "urls": [
            "https://cdn.freesound.org/previews/334/334042_4921277-hq.mp3",
            "https://cdn.freesound.org/previews/334/334042_4921277-lq.mp3",
        ],
        "use": "Small celebratory pops without a cinematic explosion.",
    },
    "glass": {
        "title": "Glass breaking.mp3", "creator": "justBrando", "license": "CC0-1.0",
        "page": "https://freesound.org/people/justBrando/sounds/159197/",
        "urls": [
            "https://raw.githubusercontent.com/ChrisAkridge/Celarix.Starfall/9c3405dbc358b47135f81fe0383d80ba5d179f0d/Celarix.Starfall.Presentations/Assets/Sounds/159197__justbrando__glass-breaking.wav"
        ],
        "use": "Real breaks split into impact, fracture and shard-tail layers.",
    },
}


def run(*cmd: str) -> None:
    print("+", " ".join(cmd), flush=True)
    subprocess.run(cmd, check=True)


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""): h.update(chunk)
    return h.hexdigest()


def download(urls: list[str], path: Path) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    errors = []
    for url in urls:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=90) as response, path.open("wb") as out:
                shutil.copyfileobj(response, out)
            if path.stat().st_size < 1024: raise RuntimeError("response too small")
            return url
        except Exception as exc:
            errors.append(f"{url}: {exc}"); path.unlink(missing_ok=True)
    raise RuntimeError("download failed\n" + "\n".join(errors))


def decode(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(src),
        "-vn", "-ac", "1", "-ar", str(SR), "-c:a", "pcm_s16le", str(dst))


def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as w:
        assert (w.getnchannels(), w.getsampwidth(), w.getframerate()) == (1, 2, SR)
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32)


def write_wav(path: Path, x: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    y = np.clip(np.rint(x), -32768, 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setparams((1, 2, SR, len(y), "NONE", "not compressed")); w.writeframes(y.tobytes())


def encode(wav: Path, ogg: Path) -> None:
    ogg.parent.mkdir(parents=True, exist_ok=True)
    run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(wav),
        "-map_metadata", "-1", "-ac", "1", "-ar", str(SR),
        "-c:a", "libvorbis", "-q:a", "4", str(ogg))


def events(x: np.ndarray, need: int, skip: float = 0, gap: float = .18, max_len: float = .72):
    n = 480; usable = x[:len(x)//n*n].reshape(-1, n) / 32768.0
    rms = np.sqrt(np.mean(usable * usable, axis=1)); rms = np.convolve(rms, np.ones(3)/3, mode="same")
    first = int(skip/.01); u = rms[first:]
    noise = max(float(np.quantile(u, .22)), .0005); dynamic = float(np.quantile(u, .92))
    found = []
    for mult in (7, 5, 3.8, 2.8, 2.1, 1.6, 1.25):
        threshold = max(noise*mult, dynamic*.10, .003)
        peaks = np.where((rms[2:-2] >= threshold) & (rms[2:-2] >= rms[1:-3]) &
                         (rms[2:-2] >= rms[3:-1]))[0] + 2
        peaks = peaks[peaks >= first]
        kept = []
        for p in peaks:
            if kept and p-kept[-1] < int(gap/.01):
                if rms[p] > rms[kept[-1]]: kept[-1] = int(p)
                continue
            kept.append(int(p))
        found = []
        for p in kept:
            floor = max(noise*1.7, float(rms[p])*.09); a=p; b=p
            while a>first and p-a<25 and rms[a]>floor: a-=1
            while b<len(rms)-1 and b-a<int(max_len/.01) and (b-p<8 or rms[b]>floor): b+=1
            s=max(0,a*n-int(.012*SR)); e=min(len(x),b*n+int(.025*SR)); d=(e-s)/SR
            if .05 <= d <= max_len+.08:
                win=x[s:e]; z=float(np.mean(np.signbit(win[1:]) != np.signbit(win[:-1]))) if len(win)>1 else 0
                if z < .42: found.append((p,s,e,float(rms[p])*(1-min(z,.35))*(.92+min(d,.35))))
        if len(found) >= need: break
    if len(found) < need: raise RuntimeError(f"detected {len(found)} events; need {need}")
    ranked=sorted(found,key=lambda v:v[3],reverse=True); selected=[]
    for item in ranked:
        if all(abs(item[0]-old[0])*.01>=.28 for old in selected): selected.append(item)
        if len(selected)>=need: break
    for item in ranked:
        if len(selected)>=need: break
        if item not in selected: selected.append(item)
    return sorted(selected,key=lambda v:v[0])


def condition(x: np.ndarray, peak=.72, max_s=None, fade_in=.003, fade_out=.018) -> np.ndarray:
    if not len(x): return np.zeros(int(.06*SR),np.float32)
    idx=np.where(np.abs(x)>=180)[0]
    if len(idx): x=x[max(0,idx[0]-int(.006*SR)):min(len(x),idx[-1]+int(.006*SR)+1)]
    if max_s: x=x[:int(max_s*SR)]
    x=x.astype(np.float32,copy=True); m=float(np.max(np.abs(x))) or 1; x*=min(6,(32767*peak)/m)
    fi=min(len(x),max(1,int(fade_in*SR))); fo=min(len(x),max(1,int(fade_out*SR)))
    x[:fi]*=np.linspace(0,1,fi,endpoint=False); x[-fo:]*=np.linspace(1,0,fo)
    return x


def mix(parts, peak=.78):
    size=max(offset+len(x) for x,gain,offset in parts); out=np.zeros(size,np.float32)
    for x,gain,offset in parts: out[offset:offset+len(x)] += x*gain
    m=float(np.max(np.abs(out))) or 1; out*=min(1,(32767*peak)/m); return out


def onepole(x: np.ndarray, cutoff: float, high=False) -> np.ndarray:
    x=x.astype(np.float32); out=np.zeros_like(x); dt=1/SR; rc=1/(2*math.pi*cutoff)
    if high:
        a=rc/(rc+dt); px=float(x[0]); py=0.
        for i,v in enumerate(x): py=a*(py+float(v)-px); out[i]=py; px=float(v)
    else:
        a=dt/(rc+dt); y=float(x[0])
        for i,v in enumerate(x): y+=a*(float(v)-y); out[i]=y
    return out


def main() -> int:
    ap=argparse.ArgumentParser(); ap.add_argument("--repo-root",type=Path,default=Path.cwd()); ap.add_argument("--clean",action="store_true")
    args=ap.parse_args(); root=args.repo_root.resolve(); raw=root/"android/app/src/main/res/raw"; docs=root/"docs/audio"; work=root/"build/audio-pack-work"
    if shutil.which("ffmpeg") is None: raise SystemExit("ffmpeg missing")
    if args.clean: shutil.rmtree(work,ignore_errors=True); raw.mkdir(parents=True,exist_ok=True); [p.unlink() for p in raw.glob("chess_*.ogg")]
    work.mkdir(parents=True,exist_ok=True); raw.mkdir(parents=True,exist_ok=True)
    downloaded={}; hashes={}; pcm={}
    ext={"board":"wav","capture":"mp3","firework":"mp3","glass":"wav"}
    for key,meta in SOURCES.items():
        src=work/f"sources/{key}.{ext[key]}"; url=download(meta["urls"],src) if not src.exists() else meta["urls"][0]
        wav=work/f"decoded/{key}.wav"; decode(src,wav); pcm[key]=read_wav(wav); downloaded[key]=url; hashes[key]=digest(src)
    records=[]
    def export(name,category,x,sources,processing):
        wav=work/f"clips/{name}.wav"; ogg=raw/f"{name}.ogg"; write_wav(wav,x); encode(wav,ogg)
        records.append({"file":ogg.name,"category":category,"duration_seconds":round(len(x)/SR,4),"sha256":digest(ogg),"source_ids":sources,"processing":processing})

    board=events(pcm["board"],72,skip=7.5,gap=.16,max_len=.62)
    idx=[round(i*(len(board)-1)/49) for i in range(50)]; move=[]
    for i,event_i in enumerate(idx,1):
        _,s,e,_=board[event_i]; clip=condition(pcm["board"][s:e],.62+.12*((i*17)%11)/10,.50); move.append(clip)
        export(f"chess_move_wood_{i:02d}","move",clip,["board"],"Distinct recorded event; trim, fade and gain staging.")

    cap_event=max(events(pcm["capture"],1,gap=.1,max_len=.7),key=lambda v:v[3]); _,s,e,_=cap_event; cap=condition(pcm["capture"][s:e],.70,.60)
    for i,(_,s,e,_) in enumerate(sorted(board,key=lambda v:v[3],reverse=True)[:12],1):
        impact=condition(pcm["board"][s:e],.64,.38); clip=condition(mix([(impact,.78-(i%3)*.05,0),(cap,.54+(i%5)*.035,int(SR*(.003+(i%4)*.004)))],.79),.76,.58)
        export(f"chess_capture_wood_{i:02d}","capture",clip,["board","capture"],"Distinct board impact layered with real chess capture.")

    for i in range(6):
        a=move[(i*7+3)%50]; b=move[(i*11+19)%50]; clip=condition(mix([(a,.78,0),(b,.82,len(a)+int(SR*(.082+i*.011)))],.74),.72,.78)
        export(f"chess_castle_wood_{i+1:02d}","castle",clip,["board"],"Two distinct real piece placements.")

    fire=events(pcm["firework"],2,gap=.45,max_len=.95)[:2]; bases=[condition(pcm["firework"][s:e],.70,.88) for _,s,e,_ in fire]
    for tier,category,cut in [("low","firework_low",2200),("mid","firework_mid",3600),("high","firework_high",5600)]:
        for i,base in enumerate(bases,1):
            clip=onepole(base,cut); clip=onepole(clip,180,True) if tier=="high" else clip; clip=condition(clip,.66 if tier=="low" else .61,.78)
            export(f"chess_firework_{tier}_{i:02d}",category,clip,["firework"],f"Recorded small pop; {tier} filtering, no explosion layer.")

    candidates=events(pcm["glass"],8,gap=.50,max_len=1.2); chosen=[]
    for ev in sorted(candidates,key=lambda v:v[3],reverse=True):
        if all(abs(ev[0]-old[0])*.01>=1.0 for old in chosen): chosen.append(ev)
        if len(chosen)==3: break
    if len(chosen)<3: chosen=candidates[:3]
    layers={"impact":[],"fracture":[],"shards":[]}
    for i,(p,_,_,_) in enumerate(sorted(chosen),1):
        q=p*480; impact=condition(onepole(pcm["glass"][max(0,q-int(.025*SR)):q+int(.20*SR)],5200),.78,.24,fade_out=.035)
        fracture=condition(onepole(pcm["glass"][max(0,q-int(.015*SR)):q+int(.58*SR)],420,True),.68,.62,fade_out=.055)
        shards=condition(onepole(pcm["glass"][q+int(.11*SR):q+int(1.05*SR)],980,True),.52,.92,fade_out=.10)
        for kind,clip,cat in [("impact",impact,"glass_impact"),("fracture",fracture,"glass_fracture"),("shards",shards,"glass_shards")]:
            layers[kind].append(clip); export(f"chess_glass_{kind}_{i:02d}",cat,clip,["glass"],f"Real glass {kind} layer; trim, filter and fade.")

    checks=[]
    for i in range(4):
        source=layers["fracture"][i%3]; start=int((.035+i*.024)*SR); clip=condition(onepole(source[start:start+int(.105*SR)],1500,True),.42,.13,fade_out=.028); checks.append(clip)
        export(f"chess_check_crystal_{i+1:02d}","check",clip,["glass"],"Very short real-glass tick for quiet layering.")
    for i in range(4):
        base=move[(i*9+5)%50]; clip=condition(mix([(base,.92,0),(checks[i],.26,max(0,len(base)-int(.035*SR)))],.72),.70,.58)
        export(f"chess_promotion_{i+1:02d}","promotion",clip,["board","glass"],"Real placement plus quiet real-glass accent.")

    soft=sorted(board,key=lambda v:v[3])[:12]
    for i in range(3):
        _,s,e,_=soft[i*2]; clip=condition(pcm["board"][s:e],.38,.24); export(f"chess_hint_{i+1:02d}","hint",clip,["board"],"Quiet real wood placement.")
    for i in range(4):
        _,s,e,_=soft[i+5]; clip=condition(pcm["board"][s:e],.34,.105); export(f"chess_low_time_{i+1:02d}","low_time",clip,["board"],"Short wood tap for a single threshold warning.")
    for i in range(3):
        a=move[(i*13+1)%50]; b=move[(i*17+8)%50]; clip=condition(mix([(a,.52,0),(b,.48,len(a)+int(.075*SR))],.48),.46,.62)
        export(f"chess_game_start_{i+1:02d}","game_start",clip,["board"],"Two soft real piece placements.")
    for i in range(3):
        clip=condition(move[(i*11+4)%50][::-1],.45,.22,fade_in=.012,fade_out=.008); export(f"chess_undo_{i+1:02d}","undo",clip,["board"],"Optional subtle reverse of a real wood tap.")

    counts={}
    for r in records: counts[r["category"]]=counts.get(r["category"],0)+1
    docs.mkdir(parents=True,exist_ok=True)
    manifest={"schema":1,"generated_utc":time.strftime("%Y-%m-%dT%H:%M:%SZ",time.gmtime()),"sample_rate_hz":SR,"channels":1,"encoding":"Ogg Vorbis quality 4",
              "sources":{k:{**v,"downloaded_from":downloaded[k],"source_sha256":hashes[k]} for k,v in SOURCES.items()},"assets":records}
    (docs/"audio_manifest.json").write_text(json.dumps(manifest,indent=2)+"\n")
    notice=["# Third-party sampled audio\n\nAll source recordings are CC0-1.0. Attribution is not required; it is preserved for provenance.\n\n"]
    for k,v in SOURCES.items(): notice += [f"## {v['title']}\n\n- Creator: {v['creator']}\n- Source: {v['page']}\n- License: CC0-1.0\n- Source SHA-256: `{hashes[k]}`\n- Use: {v['use']}\n\n"]
    notice += ["## Processing policy\n\nThe 50-move pool uses distinct recorded events, not pitch-shifted clones. Processing is limited to trimming, fades, filtering, gain staging, layering of separately recorded impacts, and Ogg encoding.\n"]
    (docs/"THIRD_PARTY_AUDIO.md").write_text("".join(notice))
    (docs/"README.md").write_text(f"""# Drawless Chess sampled audio pack\n\nAndroid-ready CC0 foley in `res/raw`, with **{counts['move']} distinct move recordings**, {counts['capture']} captures, {counts['castle']} castles, restrained firework pops, a three-stage glass-loss sequence, and optional check, promotion, hint, low-time, game-start and undo cues.\n\nUse a shuffle bag per category so every move variant plays before repeats. See `SAMPLED_AUDIO_INTEGRATION.md`, `THIRD_PARTY_AUDIO.md`, and `audio_manifest.json`.\n\nVerify with:\n\n```bash\npython3 scripts/audio/verify_sampled_audio_pack.py .\n```\n""")
    categories={}
    for r in records: categories.setdefault(r["category"],[]).append(r["file"][:-4])
    names={"move":"moves","capture":"captures","castle":"castles","firework_low":"fireworkLow","firework_mid":"fireworkMid","firework_high":"fireworkHigh","glass_impact":"glassImpact","glass_fracture":"glassFracture","glass_shards":"glassShards","check":"checkAccents","promotion":"promotions","hint":"hints","low_time":"lowTime","game_start":"gameStart","undo":"undo"}
    catalog="// Generated by scripts/audio/build_sampled_audio_pack.py.\npackage com.drawlesschess.ui\n\nimport com.drawlesschess.R\n\ninternal object SampledSoundCatalog {\n"
    for cat,prop in names.items():
        refs=",\n        ".join(f"R.raw.{n}" for n in categories.get(cat,[])); catalog += f"    val {prop} = intArrayOf(\n        {refs},\n    )\n\n"
    catalog += "}\n"; cp=root/"android/app/src/main/kotlin/com/drawlesschess/ui/SampledSoundCatalog.kt"; cp.parent.mkdir(parents=True,exist_ok=True); cp.write_text(catalog)
    legal=docs/"licenses/CC0-1.0.txt"; legal.parent.mkdir(parents=True,exist_ok=True)
    if not legal.exists(): download(["https://creativecommons.org/publicdomain/zero/1.0/legalcode.txt","https://raw.githubusercontent.com/spdx/license-list-data/main/text/CC0-1.0.txt"],legal)
    pick=[]
    for cat,n in [("move",10),("capture",4),("castle",2),("check",2),("promotion",2),("firework_low",1),("firework_mid",1),("firework_high",1),("glass_impact",1),("glass_fracture",1),("glass_shards",1),("hint",1),("low_time",1)]: pick += [raw/r["file"] for r in records if r["category"]==cat][:n]
    silence=work/"silence.wav"; write_wav(silence,np.zeros(int(.18*SR),np.float32)); silence_ogg=work/"silence.ogg"; encode(silence,silence_ogg)
    concat=work/"preview.txt"; concat.write_text("\n".join(sum(([f"file '{p.as_posix()}'",f"file '{silence_ogg.as_posix()}'"] for p in pick),[]))+"\n")
    run("ffmpeg","-hide_banner","-loglevel","error","-y","-f","concat","-safe","0","-i",str(concat),"-ac","1","-ar",str(SR),"-c:a","libvorbis","-q:a","4",str(docs/"audio-pack-preview.ogg"))
    if counts.get("move") != 50: raise RuntimeError("move count is not 50")
    print(json.dumps(counts,indent=2)); return 0


if __name__ == "__main__": raise SystemExit(main())
