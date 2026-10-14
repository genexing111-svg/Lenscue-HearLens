// Browser-independent extraction of the frozen capture-worklet.js algorithm.
// Use normalized PCM float32 input. Keep one instance per recording session.
export class AudioFramer {
  constructor(sourceSampleRate,onWindow){
    if(!Number.isFinite(sourceSampleRate)||sourceSampleRate<=0)throw new Error('Invalid sample rate');
    if(typeof onWindow!=='function')throw new Error('onWindow is required');
    this.onWindow=onWindow;this.ratio=sourceSampleRate/16000;this.weight=0;this.sum=0;
    this.ring=new Float32Array(15600);this.write=0;this.total=0;this.next=15600;
  }
  push(value){
    this.ring[this.write]=value;this.write=(this.write+1)%15600;this.total++;
    if(this.total>=this.next){
      const samples=new Float32Array(15600);let energy=0,peak=0;
      for(let i=0;i<samples.length;i++){const v=this.ring[(this.write+i)%15600];samples[i]=v;energy+=v*v;peak=Math.max(peak,Math.abs(v));}
      this.onWindow({samples,rms:Math.sqrt(energy/15600),peak,endMs:this.total/16});this.next+=7680;
    }
  }
  feed(input){
    for(const value of input){
      let remain=1;
      while(remain>1e-9){
        const n=Math.min(remain,this.ratio-this.weight);this.sum+=value*n;this.weight+=n;remain-=n;
        if(this.weight>=this.ratio-1e-9){this.push(this.sum/this.ratio);this.sum=0;this.weight=0;}
      }
    }
  }
}
