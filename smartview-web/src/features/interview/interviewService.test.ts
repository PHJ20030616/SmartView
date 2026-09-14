import axios from "axios";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { components } from "../../api/generated/schema";
import {
  createInterviewSessionApi,
  finishInterviewSessionApi,
  getInterviewSessionApi,
  submitAnswerApi,
} from "./interviewApi";
import {
  createSession,
  finishSession,
  isConflictError,
  restoreSession,
  submitAnswer,
  toInterviewError,
} from "./interviewService";

type InterviewSession = components["schemas"]["InterviewSession"];
type SubmitAnswerData = components["schemas"]["SubmitAnswerData"];
type InterviewQuestion = components["schemas"]["InterviewQuestion"];

vi.mock("./interviewApi", () => ({
  createInterviewSessionApi: vi.fn(),
  finishInterviewSessionApi: vi.fn(),
  getInterviewSessionApi: vi.fn(),
  submitAnswerApi: vi.fn(),
}));

const createSessionMock = vi.mocked(createInterviewSessionApi);
const getSessionMock = vi.mocked(getInterviewSessionApi);
const submitAnswerMock = vi.mocked(submitAnswerApi);
const finishSessionMock = vi.mocked(finishInterviewSessionApi);

function session(overrides: Partial<InterviewSession> = {}): InterviewSession {
  return {
    id: "1",
    userId: "7",
    resumeProfileId: "10",
    roleDirection: "JAVA_BACKEND",
    status: "IN_PROGRESS",
    questionCount: 1,
    expectedMinQuestions: 5,
    expectedMaxQuestions: 8,
    answers: [],
    ...overrides,
  } as InterviewSession;
}

describe("面试会话服务", () => {
  afterEach(() => vi.clearAllMocks());

  it("restoreSession 透传 sessionId 返回会话详情", async () => {
    const s = session();
    getSessionMock.mockResolvedValue(s);
    await expect(restoreSession("1")).resolves.toBe(s);
    expect(getSessionMock).toHaveBeenCalledWith("1", undefined);
  });

  it("createSession 透传画像 ID 与方向", async () => {
    const s = session();
    createSessionMock.mockResolvedValue(s);
    await expect(createSession("10", "AGENT_DEVELOPMENT")).resolves.toBe(s);
    expect(createSessionMock).toHaveBeenCalledWith("10", "AGENT_DEVELOPMENT", undefined);
  });

  it("submitAnswer 生成幂等 requestId 并携带作答耗时", async () => {
    const question: InterviewQuestion = {
      id: "11",
      sessionId: "1",
      questionOrder: 1,
      questionText: "volatile 的作用？",
    };
    const data: SubmitAnswerData = {
      answerId: "101",
      evaluation: { score: 85, level: "GOOD" },
      nextQuestion: { id: "12", sessionId: "1", questionOrder: 2, questionText: "追问" },
      sessionStatus: "IN_PROGRESS",
    };
    submitAnswerMock.mockResolvedValue(data);

    const result = await submitAnswer("1", question, "保证可见性", 42);

    expect(result).toBe(data);
    expect(submitAnswerMock).toHaveBeenCalledTimes(1);
    const [sessionId, questionId, answerText, requestId, durationSeconds] =
      submitAnswerMock.mock.calls[0];
    expect(sessionId).toBe("1");
    expect(questionId).toBe("11");
    expect(answerText).toBe("保证可见性");
    expect(requestId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);
    expect(durationSeconds).toBe(42);
  });

  it("finishSession 调用结束端点", async () => {
    const s = session({ status: "COMPLETED" });
    finishSessionMock.mockResolvedValue(s);
    await expect(finishSession("1")).resolves.toBe(s);
    expect(finishSessionMock).toHaveBeenCalledWith("1", undefined);
  });

  it("toInterviewError 提取后端 message 并保留 HTTP 状态码", () => {
    const error = { isAxiosError: true, message: "x", response: { status: 409, data: { message: "会话已推进" } } };
    const err = toInterviewError(error, "兜底");
    expect(err.status).toBe(409);
    expect(err.message).toBe("会话已推进");
  });

  it("toInterviewError 把网络超时转成中文提示并提示不要重复提交", () => {
    // axios 超时原文是英文 "timeout of 65000ms exceeded"，且此刻服务端可能仍在评估，
    // 直接展示原文会诱导用户立刻重按（白烧一次 LLM 配额），因此必须换成中文提示
    const error = {
      isAxiosError: true,
      code: "ECONNABORTED",
      message: "timeout of 65000ms exceeded",
    };
    const err = toInterviewError(error, "兜底");

    expect(err.status).toBeUndefined();
    expect(err.message).toContain("不要重复提交");
    expect(err.message).not.toContain("exceeded");
  });

  it("toInterviewError 不把主动取消误报为超时", () => {
    // 组件卸载/切页会主动 abort：axios 抛 CanceledError，靠 axios.isCancel 判定，
    // 而不是比对 message 文案（那是 axios 内部实现细节，升级后可能变）
    const error = new axios.CanceledError("canceled");

    expect(toInterviewError(error, "兜底").message).toBe("canceled");
  });

  it("isConflictError 识别 409 冲突", () => {
    const error = { isAxiosError: true, message: "x", response: { status: 409, data: {} } };
    expect(isConflictError(error)).toBe(true);
    expect(isConflictError(new Error("普通错误"))).toBe(false);
  });
});
