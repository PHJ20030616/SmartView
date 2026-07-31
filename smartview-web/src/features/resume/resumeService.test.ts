import { afterEach, describe, expect, it, vi } from "vitest";

import type { ResumeFile, ResumeVectorizationStatus } from "./resumeTypes";
import {
  getResumeFileApi,
  getResumeVectorizationStatusApi,
  retryResumeVectorizationApi,
  uploadResumeApi,
} from "./resumeApi";
import {
  retryResumeVectorization,
  uploadAndWaitForParse,
  waitForResumeVectorization,
} from "./resumeService";

vi.mock("./resumeApi", () => ({
  confirmResumeProfileApi: vi.fn(),
  getResumeFileApi: vi.fn(),
  getResumeProfileApi: vi.fn(),
  getResumeVectorizationStatusApi: vi.fn(),
  retryResumeVectorizationApi: vi.fn(),
  updateResumeProfileApi: vi.fn(),
  uploadResumeApi: vi.fn(),
}));

const uploadResumeMock = vi.mocked(uploadResumeApi);
const getResumeFileMock = vi.mocked(getResumeFileApi);
const getVectorizationStatusMock = vi.mocked(getResumeVectorizationStatusApi);
const retryVectorizationMock = vi.mocked(retryResumeVectorizationApi);

describe("简历上传解析服务", () => {
  afterEach(() => {
    vi.clearAllMocks();
    vi.useRealTimers();
  });

  it("取消信号触发后应停止等待并且不再轮询", async () => {
    const file = new File(["resume"], "resume.pdf", {
      type: "application/pdf",
    });
    const pendingResume = {
      id: "resume-file-1",
      parseStatus: "PENDING",
    } as ResumeFile;
    const controller = new AbortController();
    uploadResumeMock.mockResolvedValue(pendingResume);

    const pending = uploadAndWaitForParse(file, undefined, controller.signal);
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    controller.abort();

    await expect(pending).rejects.toMatchObject({
      name: "AbortError",
      message: "简历解析已取消",
    });
    expect(uploadResumeMock).toHaveBeenCalledWith(file, controller.signal);
    expect(getResumeFileMock).not.toHaveBeenCalled();
  });

  it("开始前已取消时不应发起上传请求", async () => {
    const file = new File(["resume"], "resume.pdf", {
      type: "application/pdf",
    });
    const controller = new AbortController();
    controller.abort();

    await expect(
      uploadAndWaitForParse(file, undefined, controller.signal),
    ).rejects.toMatchObject({
      name: "AbortError",
      message: "简历解析已取消",
    });
    expect(uploadResumeMock).not.toHaveBeenCalled();
  });
});

describe("简历向量入库轮询服务", () => {
  afterEach(() => {
    vi.clearAllMocks();
    vi.useRealTimers();
  });

  it("只有收到 SUCCESS 才结束轮询并返回成功状态", async () => {
    vi.useFakeTimers();
    getVectorizationStatusMock
      .mockResolvedValueOnce({
        resumeProfileId: "profile-1",
        profileVersion: 1,
        status: "PENDING",
        retryCount: 0,
      })
      .mockResolvedValueOnce({
        resumeProfileId: "profile-1",
        profileVersion: 1,
        status: "PROCESSING",
        retryCount: 0,
      })
      .mockResolvedValueOnce({
        resumeProfileId: "profile-1",
        profileVersion: 1,
        status: "SUCCESS",
        retryCount: 0,
        chunksCount: 4,
      });

    const statuses: string[] = [];
    const pending = waitForResumeVectorization(
      "profile-1",
      (status) => statuses.push(status.status),
    );
    await vi.advanceTimersByTimeAsync(4_000);

    await expect(pending).resolves.toMatchObject({
      status: "SUCCESS",
      chunksCount: 4,
    });
    expect(statuses).toEqual(["PENDING", "PROCESSING", "SUCCESS"]);
    expect(getVectorizationStatusMock).toHaveBeenCalledTimes(3);
  });

  it("收到 FAILED 时停止轮询并保留后端状态供页面重试", async () => {
    getVectorizationStatusMock.mockResolvedValue({
      resumeProfileId: "profile-2",
      profileVersion: 3,
      status: "FAILED",
      retryCount: 2,
      errorMessage: "Chroma 暂时不可用",
    });

    await expect(waitForResumeVectorization("profile-2")).rejects.toEqual(
      expect.objectContaining({
        name: "ResumeVectorizationError",
        message: "Chroma 暂时不可用",
        status: expect.objectContaining({ status: "FAILED" }),
      }),
    );
    expect(getVectorizationStatusMock).toHaveBeenCalledTimes(1);
  });

  it("最多等待 60 秒，超时后返回可重试错误", async () => {
    vi.useFakeTimers();
    getVectorizationStatusMock.mockResolvedValue({
      resumeProfileId: "profile-3",
      profileVersion: 1,
      status: "PROCESSING",
      retryCount: 0,
    });

    const pending = waitForResumeVectorization("profile-3");
    const timeoutAssertion = expect(pending).rejects.toEqual(
      expect.objectContaining({
        name: "ResumeVectorizationError",
        timedOut: true,
        message: "简历向量入库等待超时，请点击重试",
      }),
    );
    await vi.advanceTimersByTimeAsync(60_000);

    await timeoutAssertion;
    // 初始查询加上截止时间前的 29 次轮询；到达 60 秒时不再启动新请求。
    expect(getVectorizationStatusMock).toHaveBeenCalledTimes(30);
  });

  it("初始状态请求过慢时也必须在 60 秒截止，不等待请求无限悬挂", async () => {
    vi.useFakeTimers();
    getVectorizationStatusMock.mockImplementation(
      async (_profileId, signal) =>
        new Promise<ResumeVectorizationStatus>((_, reject) => {
          signal?.addEventListener(
            "abort",
            () => {
              const error = new Error("请求已取消");
              error.name = "AbortError";
              reject(error);
            },
            { once: true },
          );
        }),
    );

    const pending = waitForResumeVectorization("slow-profile");
    const timeoutAssertion = expect(pending).rejects.toEqual(
      expect.objectContaining({
        name: "ResumeVectorizationError",
        timedOut: true,
        message: "简历向量入库等待超时，请点击重试",
      }),
    );
    await vi.advanceTimersByTimeAsync(60_000);

    await timeoutAssertion;
    expect(getVectorizationStatusMock).toHaveBeenCalledTimes(1);
  });

  it("页面离开时取消向量轮询，不继续请求后端", async () => {
    vi.useFakeTimers();
    getVectorizationStatusMock.mockResolvedValue({
      resumeProfileId: "profile-4",
      profileVersion: 1,
      status: "PROCESSING",
      retryCount: 0,
    });
    const controller = new AbortController();
    const pending = waitForResumeVectorization(
      "profile-4",
      undefined,
      controller.signal,
    );
    // 初始状态请求已完成并进入两秒等待，取消后不应继续发起下一次查询。
    await vi.advanceTimersByTimeAsync(0);
    controller.abort();

    await expect(pending).rejects.toMatchObject({
      name: "AbortError",
      message: "简历解析已取消",
    });
    expect(getVectorizationStatusMock).toHaveBeenCalledTimes(1);
  });

  it("失败后可以创建新的向量入库重试任务", async () => {
    retryVectorizationMock.mockResolvedValue({
      resumeProfileId: "profile-5",
      profileVersion: 2,
      taskId: "task-retry-1",
      status: "PENDING",
      retryCount: 0,
    });

    await expect(retryResumeVectorization("profile-5")).resolves.toMatchObject({
      taskId: "task-retry-1",
      status: "PENDING",
    });
    expect(retryVectorizationMock).toHaveBeenCalledWith("profile-5", undefined);
  });
});
