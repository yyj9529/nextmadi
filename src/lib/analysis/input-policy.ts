import rules from "../../../backend/src/main/resources/s07_input_policy.json";

export type InputMode = "expressions" | "word";
export type InputCheck = "ready" | "choose_word_intent" | "invalid";
export const REENTER_MESSAGE = "뜻을 이해할 수 있도록 단어나 하고 싶은 말을 다시 적어주세요.";

/** Conservative lexical/utterance rules, shared with the backend. Not a semantic classifier. */
export function checkAnalysisInput(input: string): InputCheck {
  const text = input.normalize("NFC").trim();
  const letters = text.replace(/[^\p{L}\p{N}]/gu, "");
  const jamo = (letters.match(/[ㄱ-ㅎㅏ-ㅣᄀ-ᇿ]/gu) ?? []).length;
  if (!letters || (jamo >= 4 && jamo / letters.length > 0.5) || /^[ㄱ-ㅎㅏ-ㅣᄀ-ᇿ]+$/u.test(letters)) return "invalid";
  const lexical = text.toLowerCase().replace(/[.!?。！？]+$/u, "").trim();
  if (rules.utterances.includes(lexical)) return "ready";
  if (rules.nouns.includes(lexical)) return "choose_word_intent";
  if (rules.speech_endings.some((ending) => lexical.endsWith(ending))) return "ready";
  // A bare lexeme has no recognized speech act. Hyphenated English words are included.
  if (/^[a-z]+(?:[-'][a-z]+)*$|^[가-힣]+$/u.test(lexical)) return "choose_word_intent";
  return "ready";
}
