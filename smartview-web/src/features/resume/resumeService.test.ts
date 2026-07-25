import { afterEach, describe, expect, it, vi } from "vitest";

import type { ResumeFile } from "./resumeTypes";
import { getResumeFileApi, uploadResumeApi } from "./resumeApi";
import { uploadAndWaitForParse } from "./resumeService";

vi.mock("./resumeApi", () => ({
  confirmResumeProfileApi: vi.fn(),
  getResumeFileApi: vi.fn(),
  getResumeProfileApi: vi.fn(),
  updateResumeProfileApi: vi.fn(),
  uploadResumeApi: vi.fn(),
}));

const uploadResumeMock = vi.mocked(uploadResumeApi);
const getResumeFileMock = vi.mocked(getResumeFileApi);

describe("简历上传解析服务", () => {
  afterEach(() => {
    vi.clearAllMocks();
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
