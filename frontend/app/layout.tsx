import type { Metadata } from "next";
import { Inter, Crimson_Pro, JetBrains_Mono } from "next/font/google";
import "./globals.css";

const inter = Inter({
  subsets: ["latin"],
  weight: ["400", "500", "600"],
  variable: "--font-inter",
});

const crimson = Crimson_Pro({
  subsets: ["latin"],
  weight: ["400", "500"],
  variable: "--font-crimson",
});

const mono = JetBrains_Mono({
  subsets: ["latin"],
  weight: ["400", "500"],
  variable: "--font-jetbrains-mono",
});

export const metadata: Metadata = {
  title: "Devroom",
  description: "Chat with @-mentionable AI mentors across your team's channels.",
  openGraph: {
    title: "Devroom",
    description: "Chat with @-mentionable AI mentors across your team's channels.",
    type: "website",
    locale: "en_US",
  },
  twitter: {
    card: "summary",
    title: "Devroom",
    description: "Chat with @-mentionable AI mentors across your team's channels.",
  },
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en" className={`${inter.variable} ${crimson.variable} ${mono.variable}`}>
      <body className="bg-cream text-text-primary font-sans antialiased min-h-screen">
        {children}
      </body>
    </html>
  );
}
