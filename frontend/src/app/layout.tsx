import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "ChessProbe",
  description: "Recurring chess weaknesses analysis and practice",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html
      lang="en"
      className="dark h-full antialiased"
    >
      <body className="min-h-full flex flex-col bg-surface text-on-surface selection:bg-primary-container selection:text-on-primary-container">
        {children}
      </body>
    </html>
  );
}
