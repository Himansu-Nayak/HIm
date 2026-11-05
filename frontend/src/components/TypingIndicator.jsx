import { motion, AnimatePresence } from 'framer-motion';
import { Bot, Sparkles, Lightbulb, Zap } from 'lucide-react';
import { useState, useEffect } from 'react';

const CURIOSITY_FACTS = [
  "Did you know? AI can now identify emotions in human voices with 90% accuracy! 🎭",
  "Interesting fact: The first AI program was written back in 1951! 📜",
  "Fun fact: ChatGPT reached 100 million users in just 2 months! 🚀",
  "Curiosity: AI helps astronomers find new planets hidden in starlight. 🌌",
  "Fact: Some AIs can now translate over 200 different languages in real-time! 🌍",
  "Wow! AI models use billions of parameters to understand your intent. 🧠",
  "Cool: AI is being used to predict weather patterns weeks in advance. 🌦️",
  "Did you know? Modern AI can generate art that looks just like oil paintings. 🎨"
];

const TypingIndicator = () => {
  const [factIndex, setFactIndex] = useState(0);

  useEffect(() => {
    const interval = setInterval(() => {
      setFactIndex((prev) => (prev + 1) % CURIOSITY_FACTS.length);
    }, 4000);
    return () => clearInterval(interval);
  }, []);

  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: -20 }}
      className="flex items-start space-x-3 mb-6"
    >
      <motion.div
        initial={{ scale: 0, rotate: -20 }}
        animate={{ scale: 1, rotate: 0 }}
        className="flex-shrink-0 w-10 h-10 rounded-xl bg-gradient-to-br from-warm-400 via-sand-500 to-warm-600 flex items-center justify-center shadow-lg relative"
      >
        <Bot className="w-5 h-5 text-white" />
        <motion.div
          animate={{ scale: [1, 1.2, 1] }}
          transition={{ duration: 1.5, repeat: Infinity }}
          className="absolute -top-1 -right-1"
        >
          <Sparkles className="w-3 h-3 text-yellow-300" />
        </motion.div>
      </motion.div>
      
      <motion.div
        initial={{ opacity: 0, scale: 0.9 }}
        animate={{ opacity: 1, scale: 1 }}
        className="bg-navy-800 text-warm-100 px-6 py-4 rounded-2xl rounded-bl-md border border-navy-700 shadow-xl max-w-sm"
      >
        <div className="flex items-center space-x-2 mb-3">
          <div className="flex space-x-1">
            <motion.div className="w-1.5 h-1.5 bg-warm-500 rounded-full" animate={{ opacity: [0.3, 1, 0.3] }} transition={{ duration: 0.8, repeat: Infinity, delay: 0 }} />
            <motion.div className="w-1.5 h-1.5 bg-warm-500 rounded-full" animate={{ opacity: [0.3, 1, 0.3] }} transition={{ duration: 0.8, repeat: Infinity, delay: 0.2 }} />
            <motion.div className="w-1.5 h-1.5 bg-warm-500 rounded-full" animate={{ opacity: [0.3, 1, 0.3] }} transition={{ duration: 0.8, repeat: Infinity, delay: 0.4 }} />
          </div>
          <span className="text-[10px] uppercase font-bold tracking-tighter text-sand-500">HIm is thinking... ✨</span>
        </div>

        <div className="relative h-12 overflow-hidden">
          <AnimatePresence mode="wait">
            <motion.div
              key={factIndex}
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -10 }}
              transition={{ duration: 0.5 }}
              className="flex items-start space-x-2"
            >
              <Zap className="w-3 h-3 text-warm-400 mt-0.5 flex-shrink-0" />
              <p className="text-xs text-sand-300 leading-relaxed italic">
                {CURIOSITY_FACTS[factIndex]}
              </p>
            </motion.div>
          </AnimatePresence>
        </div>
      </motion.div>
    </motion.div>
  );
};

export default TypingIndicator;