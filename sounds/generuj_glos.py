import subprocess, wave, struct, math
SR=22050
def read(fn):
    w=wave.open(fn); n=w.getnframes(); sr=w.getframerate(); d=w.readframes(n); w.close()
    a=[x/32768 for x in struct.unpack('<%dh'%n,d)]; return a,sr
def ding():
    out=[]
    for f,st,dur,dec in [(880,0,0.5,0.18),(1318.5,0.12,0.6,0.22)]:
        s=int(st*SR)
        for i in range(int(dur*SR)):
            t=i/SR; v=(math.sin(2*math.pi*f*t)+0.3*math.sin(4*math.pi*f*t))*min(1,t/0.004)*math.exp(-t/dec)*0.6
            j=s+i
            if j>=len(out): out.extend([0.0]*(j-len(out)+1))
            out[j]+=v
    return out
def make(name,text):
    subprocess.run(['espeak-ng','-v','pl','-s','135','-p','55','-a','180','-w','raw_'+name+'.wav',text],check=True)
    subprocess.run(['ffmpeg','-y','-loglevel','error','-i','raw_'+name+'.wav','-ac','1','-ar',str(SR),'-af','highpass=f=120,acompressor=threshold=0.1:ratio=3,loudnorm=I=-14:TP=-1.5','v_'+name+'.wav'],check=True)
    v,_=read('v_'+name+'.wav')
    # trim silence
    th=0.01; a=next(i for i,x in enumerate(v) if abs(x)>th); b=len(v)-next(i for i,x in enumerate(reversed(v)) if abs(x)>th)
    v=v[a:b]
    out=ding()+[0.0]*int(0.05*SR)+v+[0.0]*int(0.15*SR)
    m=max(abs(x) for x in out); out=[x*0.92/m for x in out]
    w=wave.open(name+'.wav','wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes(b''.join(struct.pack('<h',int(x*32767)) for x in out)); w.close()
    print(name, len(out)/SR,'s')
make('glos_wykonaj','Wykonaj zadanie!')
make('glos_czas','Hej! Czas na zadanie!')
