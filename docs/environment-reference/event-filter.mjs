// Presentation rules for a general-purpose classifier; these are not calibrated probabilities.
// Extra filtering is limited to insects and snakes, including their related labels.
const CAUTIOUS=new Set([121,122,123,124,125,126,129,130]);
// Lower presentation thresholds only for Knock and Tap; raw model scores stay unchanged.
const SENSITIVE_KNOCK=new Set([353,354]);
const minimumScore=index=>SENSITIVE_KNOCK.has(index)?0.12:0.25;
const immediateScore=index=>SENSITIVE_KNOCK.has(index)?0.45:0.70;
// These labels may help estimate background level. They are NOT hidden or gated.
const BACKGROUND=new Set([67,68,81,103,494,500,501,502,503,504,505,506,507,508,509,510,511,512,513,514,515,520]);
const MIN_RMS=0.0015, INITIAL_FLOOR=0.0025, MAX_FLOOR=0.025;
export class EventFilter {
  constructor(){this.reset();}
  reset(){this.floor=INITIAL_FLOOR;this.noise=[];this.calibration=0;this.streaks=new Map();this.lastEndMs=null;this.candidate=null;this.candidateCount=0;}
  learn(rms){
    this.noise.push(Math.min(MAX_FLOOR,Math.max(MIN_RMS/2,rms)));
    if(this.noise.length>40)this.noise.shift();
    const sorted=[...this.noise].sort((a,b)=>a-b);
    const target=sorted[Math.floor((sorted.length-1)*0.2)];
    this.floor=this.calibration<4?target:this.floor*0.9+target*0.1;
    this.calibration++;
  }
  evaluate({categories=[],rms=0,endMs=0}){
    rms=Number.isFinite(rms)&&rms>0?rms:0;
    const list=categories.filter(c=>Number.isInteger(c.index)&&c.index>=0&&c.index<521&&Number.isFinite(c.score)&&c.score>=0&&c.score<=1).sort((a,b)=>b.score-a.score);
    if(this.lastEndMs!==null&&endMs-this.lastEndMs>800){this.streaks.clear();this.candidate=null;this.candidateCount=0;}
    this.lastEndMs=endMs;
    const ordinaryForeground=list.some(c=>!CAUTIOUS.has(c.index)&&!BACKGROUND.has(c.index)&&c.score>=minimumScore(c.index));
    const loudCautious=list.some(c=>CAUTIOUS.has(c.index)&&c.score>=0.70)&&rms>=0.03;
    // Learn in the background; there is no startup wait or volume gate for ordinary sounds.
    if(!ordinaryForeground&&!loudCautious)this.learn(rms);
    const cautious=list.filter(c=>CAUTIOUS.has(c.index)&&c.score>=0.70&&rms>Math.max(MIN_RMS,this.floor*2.5));
    const previous=this.streaks;this.streaks=new Map();
    for(const c of cautious)this.streaks.set(c.index,(previous.get(c.index)||0)+1);
    const visible=list.filter(c=>!CAUTIOUS.has(c.index)||(this.streaks.get(c.index)||0)>=3);
    const lead=visible.find(c=>c.score>=minimumScore(c.index));
    const foreground=visible.find(c=>!BACKGROUND.has(c.index)&&c.score>=minimumScore(c.index));
    const knock=visible.find(c=>SENSITIVE_KNOCK.has(c.index)&&c.score>=minimumScore(c.index));
    // A specific knock can take precedence over a generic background label, but not stronger speech or another foreground sound.
    const top=knock&&lead&&BACKGROUND.has(lead.index)?foreground:lead;
    // Keep a qualifying knock visible even when several broader labels rank ahead of it.
    const candidates=knock&&!visible.slice(0,3).includes(knock)?[...visible.slice(0,2),knock]:visible;
    if(top){
      if(top.index===this.candidate)this.candidateCount++;else{this.candidate=top.index;this.candidateCount=1;}
      if(top.index===494)return this.result('quiet');
      const confirmed=CAUTIOUS.has(top.index)||top.score>=immediateScore(top.index)||this.candidateCount>=2;
      return this.result(confirmed?'event':'possible',candidates,top);
    }
    this.candidate=null;this.candidateCount=0;
    if(cautious.length)return this.result('confirming');
    if(rms<=Math.max(MIN_RMS,this.floor*1.8))return this.result('quiet');
    return this.result('uncertain',candidates);
  }
  result(kind,candidates=[],event=null){return {kind,candidates:candidates.slice(0,3),event,noiseFloor:this.floor,calibrationWindows:this.calibration};}
}
