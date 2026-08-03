import argparse
import concurrent.futures
import logging
import os
import sys
from pathlib import Path

import dotenv

# 将本地 gptpdf 包目录加入 sys.path，确保加载本项目改过的代码而非 pip 安装的旧版本
REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT))

# 优先加载 gptpdf 目录下 .env，再加载 SmartView 根目录 .env，避免脚本在其它工作目录运行时读不到密钥配置
dotenv.load_dotenv(REPO_ROOT / '.env')
dotenv.load_dotenv(REPO_ROOT.parent / '.env')

# API 密钥只允许通过环境变量或仓库根目录 .env 提供，不写入硬编码密钥
DEFAULT_API_KEY = os.getenv('GPT_PDF_API_KEY') or os.getenv('OPENAI_API_KEY')
DEFAULT_BASE_URL = os.getenv('GPT_PDF_BASE_URL') or os.getenv('OPENAI_BASE_URL') or 'https://llm-4gq7vr8zzalyz6cg.cn-beijing.maas.aliyuncs.com/compatible-mode/v1'
DEFAULT_MODEL = os.getenv('GPT_PDF_MODEL') or 'qwen3.7-flash'

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')


def positive_int(value):
    """校验命令行传入的并发参数必须为正整数。"""
    parsed = int(value)
    if parsed < 1:
        raise argparse.ArgumentTypeError('必须大于等于 1')
    return parsed


def find_pdf_files(input_dir: Path):
    """递归查找目录下所有PDF文件。"""
    if not input_dir.is_dir():
        raise NotADirectoryError(f'输入目录不存在：{input_dir}')
    return sorted(p for p in input_dir.rglob('*') if p.is_file() and p.suffix.lower() == '.pdf')


def build_tasks(pdf_files, input_dir, output_dir, flatten):
    """
    生成 (pdf路径, 输出md路径) 任务列表。
    默认在输出目录下保留与输入目录一致的相对结构，避免同名PDF互相覆盖。
    """
    tasks = []
    used_outputs = set()
    for pdf_path in pdf_files:
        md_name = f'{pdf_path.stem}.md'
        if flatten:
            # 平铺模式：全部放到输出根目录，同名文件追加序号避免覆盖
            output_path = output_dir / md_name
            suffix = 1
            # ?????????????????????????????????
            while output_path.exists() or output_path in used_outputs:
                output_path = output_dir / f'{pdf_path.stem}_{suffix}.md'
                suffix += 1
        else:
            relative_dir = pdf_path.parent.relative_to(input_dir)
            output_path = output_dir / relative_dir / md_name
        used_outputs.add(output_path)
        tasks.append((pdf_path, output_path))
    return tasks


def convert_one_pdf(task):
    """在独立子进程中转换单个PDF；异常被捕获，不中断其他PDF任务。"""
    from gptpdf import parse_pdf

    pdf_path, output_md_path, api_key, base_url, model, gpt_worker = task
    try:
        output_md_path.parent.mkdir(parents=True, exist_ok=True)
        content, _ = parse_pdf(
            str(pdf_path),
            output_dir=str(output_md_path.parent),
            output_file=str(output_md_path),
            api_key=api_key,
            base_url=base_url,
            model=model,
            gpt_worker=gpt_worker,
            save_images=False,
        )
        return {'ok': True, 'pdf': str(pdf_path), 'output': str(output_md_path), 'chars': len(content)}
    except Exception as exc:
        logging.exception('转换失败：%s', pdf_path)
        return {'ok': False, 'pdf': str(pdf_path), 'error': str(exc)}


def main():
    parser = argparse.ArgumentParser(description='递归转换文件夹内所有PDF为Markdown（每个PDF一个子进程，最多5个并发）')
    parser.add_argument('--input-dir', default='knowledge/ai-base', help='待转换的PDF文件夹')
    parser.add_argument('--output-dir', default='knowledge/interview_knowledge_base', help='Markdown输出文件夹')
    parser.add_argument('--max-processes', type=positive_int, default=5, help='并发的PDF转换子进程数，默认5')
    parser.add_argument('--gpt-worker', type=positive_int, default=4, help='单个PDF内部页级并发线程数，默认4')
    parser.add_argument('--model', default=DEFAULT_MODEL, help='视觉模型名称')
    parser.add_argument('--flatten', action='store_true', help='将所有md平铺到输出根目录（默认保留相对目录结构）')
    args = parser.parse_args()

    # 密钥只从环境变量/.env读取，缺失时给出明确中文提示并退出
    if not DEFAULT_API_KEY:
        parser.error('未找到 API 密钥：请在仓库根目录 .env 中设置 GPT_PDF_API_KEY 或 OPENAI_API_KEY')

    input_dir = Path(args.input_dir)
    output_dir = Path(args.output_dir)

    try:
        pdf_files = find_pdf_files(input_dir)
    except NotADirectoryError as exc:
        logging.error(str(exc))
        return 1

    if not pdf_files:
        logging.warning('目录下未找到任何PDF文件：%s', input_dir)
        return 0

    tasks = [
        (pdf_path, output_md_path, DEFAULT_API_KEY, DEFAULT_BASE_URL, args.model, args.gpt_worker)
        for pdf_path, output_md_path in build_tasks(pdf_files, input_dir, output_dir, args.flatten)
    ]

    logging.info('共发现 %d 个PDF文件，使用 %d 个子进程并发转换', len(tasks), args.max_processes)

    success_count = 0
    failed = []
    with concurrent.futures.ProcessPoolExecutor(max_workers=args.max_processes) as executor:
        # 逐任务提交而不是用 map，保证某个子进程异常时其余任务仍能继续处理
        futures = {executor.submit(convert_one_pdf, task): task for task in tasks}
        for future in concurrent.futures.as_completed(futures):
            try:
                result = future.result()
            except Exception as exc:
                # 子进程崩溃等异常只记录，不中断剩余任务
                task = futures[future]
                failed.append({'pdf': str(task[0]), 'error': str(exc)})
                logging.error('子进程异常：%s，原因：%s', task[0], exc)
                continue
            if result['ok']:
                success_count += 1
                logging.info('转换完成：%s -> %s（%d字符）', result['pdf'], result['output'], result['chars'])
            else:
                failed.append(result)
                logging.error('转换失败：%s，原因：%s', result['pdf'], result['error'])

    logging.info('全部任务结束：成功 %d 个，失败 %d 个', success_count, len(failed))
    return 1 if failed else 0


if __name__ == '__main__':
    raise SystemExit(main())
