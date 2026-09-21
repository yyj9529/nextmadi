"use client";

import { useState } from "react";
import { TextInputSheet } from "./AnalysisModals";

export function AnalysisFollowUp({ inputText }: { inputText: string }) {
  const [open, setOpen] = useState(false);
  return <>
    <button type="button" className="primary-button" onClick={() => setOpen(true)}>입력 보충하기</button>
    <TextInputSheet open={open} onClose={() => setOpen(false)} initialText={inputText} />
  </>;
}
