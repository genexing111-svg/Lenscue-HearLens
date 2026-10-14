/** Actual bridge protocol; not the provider's raw WebSocket protocol. */
export type Engine = 'asr' | 'live';
export type TargetLanguage = 'en' | 'zh';
export type OutputMode = 'original' | 'zh' | 'en' | 'zh-Hant';
export type TranslationLanguage = Exclude<OutputMode, 'original'>;

export interface BackendConfig {
  configured: boolean;
  model: string;
  region: string;
  frameMs: number;
  defaultEngine: Engine;
  engines: Array<{ id: Engine; model: string; translation: boolean; speakerDetection: boolean }>;
}

export interface Word {
  text: string;
  begin_time?: number | null;
  end_time?: number | null;
  punctuation?: string;
}

export interface AsrSentence {
  sentence_id?: number;
  begin_time?: number | null;
  end_time?: number | null;
  text: string;
  sentence_begin?: boolean;
  sentence_end: boolean;
  words?: Word[];
  [providerField: string]: unknown;
}

export interface LiveSegment {
  id: string;
  speakerId: string | null;
  startMs: number | null;
  endMs: number | null;
  original: string;
  originalFinal: boolean;
  translations: Partial<Record<TranslationLanguage, string>>;
  translationFinal: Partial<Record<TranslationLanguage, boolean>>;
}

export type ServerMessage =
  | { type: 'ready'; engine: Engine; model: string; readyMs: number; frameMs: number; target?: TargetLanguage }
  | { type: 'configured'; target: TargetLanguage }
  | { type: 'result'; sentence: AsrSentence }
  | { type: 'segment'; segment: LiveSegment }
  | { type: 'restart_required'; target: TargetLanguage }
  | { type: 'error'; message: string }
  | { type: 'finished' };

export type ClientControlMessage =
  | { type: 'stop' }
  | { type: 'configure'; target: TargetLanguage };

/** Send PCM16LE as a binary frame, separately from JSON control messages. */
export type ClientAudioMessage = ArrayBuffer | Uint8Array;
