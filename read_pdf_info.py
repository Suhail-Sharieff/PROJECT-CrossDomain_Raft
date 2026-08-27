import pypdf

reader = pypdf.PdfReader("cd-raft.pdf")
print("Total Pages:", len(reader.pages))

# Extract text from first 5 pages and write to file
with open("pdf_summary.txt", "w", encoding="utf-8") as f:
    f.write("=== CD-RAFT PDF EXTRACT ===\n\n")
    for i in range(min(5, len(reader.pages))):
        f.write(f"--- PAGE {i+1} ---\n")
        f.write(reader.pages[i].extract_text())
        f.write("\n\n")
    
    # Also extract the last page for conclusion
    f.write(f"--- LAST PAGE {len(reader.pages)} ---\n")
    f.write(reader.pages[-1].extract_text())

print("Summary written to pdf_summary.txt")
