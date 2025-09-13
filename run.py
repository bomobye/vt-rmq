"""
Veri setini yükleyip inceledikten sonra aşağıdaki durumları tespit ettim.
    1-) "Email Text" kolonunda 16 satırda NaN var. (df.isna().sum())
    2-) "Email Text" kolonunda 3 satırda sadece newLine karakteri var. (df[df["Email Text"].str.strip() == ""])
    3-) "Email Text" kolonunda tekrarlayan aynı mail textleri var. (df.duplicated(subset=["Email Text"]).sum())
    4-) Bazı karakterlerde bozulmuş kodlamalar gördüm. (unicode) 
    4-) Preprocessor adımından önce bakıldığında 11.322 adet Safe Email ve 7.328 adet Phishing Email sınıfı var. (df["Email Type"].value_counts())
"""

import pandas as pd
import re
import warnings
import html
import tiktoken
import time
from bs4 import BeautifulSoup, MarkupResemblesLocatorWarning
from ftfy import fix_text

warnings.filterwarnings("ignore", category=MarkupResemblesLocatorWarning)

class PhishingEmailPreprocessor:

    def __init__(self, csv_path:str, train_frac:float, validation_frac:float, test_frac:float):

        self.df = None
        self.train_df = None
        self.validation_df = None
        self.test_df = None

        self.train_frac = train_frac
        self.validation_frac = validation_frac
        self.test_frac = test_frac
        self.csv_path = csv_path

        self.load_and_prepare(self.csv_path)   

        self.tokenizer = tiktoken.get_encoding("gpt2")
        self.pad_token_id = 50256

    def strip_html(self,text: str) -> str:
        if not isinstance(text, str):
            return ""    
        text = html.unescape(text)
        soup = BeautifulSoup(text, "html.parser")
        return soup.get_text(separator=" ")

    def normalize_whitespace(self,text: str) -> str:
        if not isinstance(text, str):
            return ""        
        text = re.sub(r"\s+", " ", text) 
        return text.strip()

    def fix_unicode(self,text: str) -> str:
        if not isinstance(text, str): 
            return ""
        return fix_text(text)

    def create_balanced_dataset(self):
        
        # Count the instances of "Phishing Email"
        num_spam = self.df[self.df["Email Type"] == 1].shape[0]
        
        # Randomly sample "Safe Email" instances to match the number of "Phishing Email" instances
        ham_subset = self.df[self.df["Email Type"] == 0].sample(num_spam, random_state=123)
        
        # Combine ham "subset" with "Phishing Email"
        self.df = pd.concat([ham_subset, self.df[self.df["Email Type"] == 1]]) 

    def load_and_prepare(self,csv_path: str):
        self.df = pd.read_csv(csv_path, sep=",", header=0, quotechar='"')

        # 1) Erken filtreler
        self.df = self.df.dropna(subset=["Email Text", "Email Type"])
        self.df = self.df[self.df["Email Text"].astype(str).str.strip() != ""]

        # 2) Unicode onarımı (mojibake)
        self.df["Email Text"] = self.df["Email Text"].apply(self.fix_unicode)

        # 3) HTML temizliği + entity decode
        self.df["Email Text"] = self.df["Email Text"].apply(self.strip_html)

        # 4) Boşluk normalizasyonu
        self.df["Email Text"] = self.df["Email Text"].apply(self.normalize_whitespace)

        # 5) Temizlenmiş metinde boş kalanları at
        self.df = self.df[self.df["Email Text"].str.len() > 0]

        # 6) Temiz metne göre duplicate temizliği
        self.df = self.df.drop_duplicates(subset=["Email Text"]).copy()

        # 7) Label map + olası hatalı label'ları düşür
        self.df["Email Type"] = self.df["Email Type"].map({"Safe Email": 0, "Phishing Email": 1})
        self.df = self.df.dropna(subset=["Email Type"])
        self.df["Email Type"] = self.df["Email Type"].astype(int)
        self.create_balanced_dataset()
        self.random_split()

    def random_split(self):
        # Shuffle the entire DataFrame
        self.df = self.df.sample(frac=1, random_state=123).reset_index(drop=True)

        # Calculate split indices
        train_end = int(len(self.df) * self.train_frac)
        validation_end = train_end + int(len(self.df) * self.validation_frac)

        # Split the DataFrame
        self.train_df = self.df[:train_end]
        self.validation_df = self.df[train_end:validation_end]
        self.test_df = self.df[validation_end:]

    def encode_and_pad(self, data):
        # Pre-tokenize texts
        start = time.time()
        encoded_texts = [
            self.tokenizer.encode(text) for text in data["Email Text"]
        ]
        end = time.time()

        print("Tokenize:", end-start)
        start = time.time()
        max_length = 0
        for encoded_text in encoded_texts:
            encoded_length = len(encoded_text)
            if encoded_length > max_length:
                max_length = encoded_length

        end = time.time()
        print("max_length:", max_length, ", ",end-start)

        # Pad sequences to the longest sequence
        encoded_texts = [
            encoded_text + [self.pad_token_id] * (max_length - len(encoded_text))
            for encoded_text in encoded_texts
        ] 

        return encoded_texts
    
    def get_dfs(self) -> pd.DataFrame:
        return self.train_df, self.validation_df, self.test_df
