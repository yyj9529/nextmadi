import type { InputMode } from "./input-policy";

export type AnalysisClientOptions = { inputMode?: InputMode; idempotencyKey?: string };
export type AnalysisCreated = { analysis_request_id: string };

export async function postAnalysisRequest(inputText: string, signal: AbortSignal,
  options: AnalysisClientOptions = {}): Promise<AnalysisCreated> {
  const response = await fetch("/api/analysis", {
    method: "POST", credentials: "same-origin", signal,
    headers: { "content-type": "application/json", ...(options.idempotencyKey ? { "idempotency-key": options.idempotencyKey } : {}) },
    body: JSON.stringify({ input_text: inputText, input_mode: options.inputMode }),
  });
  const body: unknown = await response.json().catch(() => null);
  if (!response.ok) throw body ?? new Error(`analysis failed: ${response.status}`);
  if (typeof body !== "object" || body === null || !("analysis_request_id" in body)
      || typeof body.analysis_request_id !== "string") throw new Error("invalid analysis response");
  return { analysis_request_id: body.analysis_request_id };
}

/** Same operation on network retry, new operation after edits (including edit-and-revert). */
export class AnalysisOperation {
  private key: string | undefined;
  reset() { this.key = undefined; }
  current() { return this.key ??= crypto.randomUUID(); }
}
