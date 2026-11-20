import math, struct, wave, sys, os
SR=22050
def env(t, a, d):  # attack/decay exp
    return min(1, t/a) * math.exp(-t/d)
def tone(freqs, dur, d=0.4, a=0.005, harm=(1,0.5,0.25)):
    out=[]
    for i in range(int(SR*dur)):
        t=i/SR; v=0
        for f in freqs:
            for k,h in enumerate(harm): v+=h*math.sin(2*math.pi*f*(k+1)*t)
        out.append(v*env(t,a,d)/len(freqs))
    return out
def silence(d): return [0.0]*int(SR*d)
def mix_at(buf, part, at):
    s=int(at*SR)
    need=s+len(part)
    if len(buf)<need: buf.extend([0.0]*(need-len(buf)))
    for i,v in enumerate(part): buf[s+i]+=v
    return buf
def norm(b, peak=0.9):
    m=max(abs(x) for x in b) or 1
    return [x*peak/m for x in b]
def save(name,b):
    b=norm(b)
    w=wave.open(name,'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes(b''.join(struct.pack('<h',int(x*32767)) for x in b)); w.close()
# dzwonek: ding-dong x2
b=[]
for r in range(2):
    mix_at(b,tone([659.25],0.9,0.35),r*1.1); mix_at(b,tone([523.25],1.0,0.45),r*1.1+0.4)
save('dzwonek.wav',b)
# gong: deep, long
b=tone([196,293.7*1.01,392*0.99],2.6,0.9,0.01,(1,0.6,0.4,0.2)); save('gong.wav',b)
# budzik: fast beeps 4x4
b=[]
for g in range(3):
    for k in range(4): mix_at(b,tone([1760],0.09,1,0.002,(1,0.3)),g*0.7+k*0.12)
save('budzik.wav',b)
# melodia: arpeggio C E G C
b=[]
for k,f in enumerate([523.25,659.25,783.99,1046.5,783.99,1046.5]): mix_at(b,tone([f],0.6,0.25),k*0.22)
save('melodia.wav',b)
# syrena: sweeps
b=[]; ph=0
for i in range(int(SR*2.4)):
    t=i/SR; f=700+500*(0.5-0.5*math.cos(2*math.pi*t/0.8)); ph+=2*math.pi*f/SR
    b.append((math.sin(ph)+0.3*math.sin(2*ph))*min(1,t/0.02)*min(1,(2.4-t)/0.1))
save('syrena.wav',b)
