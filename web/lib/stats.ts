/**
 * The site's headline figures, with a declared shape.
 *
 * Importing stats.json directly worked only while every array in it had
 * something in it: TypeScript infers `never[]` from `[]`, so the first
 * release with nothing measured yet broke the build on `h.name`. The shape
 * belongs in one place anyway, because three pages read these fields.
 *
 * `hasObservations` is the field to branch on. It is false until somebody has
 * actually walked a block, and while it is false every observation figure
 * below is zero -- not because the streets are clean, but because nobody has
 * looked. Pages must not present those zeros as findings.
 */
import raw from "@/public/stats.json";

export interface Hotspot {
  name: string;
  piecesPer100m: number;
  index: number;
}

export interface CompositionSlice {
  label: string;
  share: number;
  key: string;
}

export interface Crew {
  name: string;
  color: string;
}

export interface Stats {
  neighborhood: string;
  city: string;
  segments: number;
  surveyedSegments: number;
  coveragePct: number;
  unwalkedSegments: number;
  networkKm: number;
  generators: number;
  /** False until a real survey exists. Everything below is zero when false. */
  hasObservations: boolean;
  license: string;
  note: string;
  pieces: number;
  piecesPer100m: number;
  singleUsePct: number;
  historyDays: number;
  crews: Crew[];
  heldSegments: number;
  composition: CompositionSlice[];
  hotspots: Hotspot[];
}

const stats = raw as Stats;
export default stats;
