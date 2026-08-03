import os
import dotenv
dotenv.load_dotenv()
# API 密钥通过环境变量或 .env 提供，避免硬编码密钥进入版本库
api_key = os.getenv('GPT_PDF_API_KEY') or os.getenv('OPENAI_API_KEY')
base_url = 'https://llm-4gq7vr8zzalyz6cg.cn-beijing.maas.aliyuncs.com/compatible-mode/v1'

pdf_path = 'knowledge\\interview_knowledge_base\\AI大模型原理和应用面试题速记通关版 _ 面试刷题 mianshiya.com.pdf'
output_dir = 'knowledge\\interview_knowledge_base_md\\01'

# pdf_path = 'gptpdf\\examples\\rh.pdf'
# output_dir = 'gptpdf\\examples\\rh'

# 清空output_dir
# import shutil
# shutil.rmtree(output_dir, ignore_errors=True)

def test_parse_pdf():
    from gptpdf import parse_pdf
    content, image_paths = parse_pdf(
        pdf_path, 
        output_dir=output_dir, 
        api_key=api_key, 
        base_url=base_url, 
        model='qwen3.7-flash', 
        gpt_worker=6
        )
    print(content)
    print(image_paths)


if __name__ == '__main__':
    test_parse_pdf()
