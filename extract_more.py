import pypdf

reader = pypdf.PdfReader("cd-raft.pdf")
print("Total Pages:", len(reader.pages))

with open("pdf_detailed_notes.txt", "w", encoding="utf-8") as f:
    for i in range(5, len(reader.pages)):
        f.write(f"--- PAGE {i+1} ---\n")
        f.write(reader.pages[i].extract_text())
        f.write("\n\n")

print("Detailed pages written to pdf_detailed_notes.txt")
